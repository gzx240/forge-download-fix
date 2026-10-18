package forge.gui.download;

import com.google.common.collect.Iterables;
import forge.StaticData;
import forge.card.CardEdition;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.util.ImageUtil;
import forge.util.ScryfallRateLimiter;
import forge.util.TextUtil;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Downloads card images for all cards matching the predicate.
 *
 * <p>Per face, URL resolution prefers:
 * <ol>
 *   <li>the current printing's cached CDN URL,</li>
 *   <li>any cached CDN URL for another printing of the same card name,</li>
 *   <li>the rate-limited Scryfall API,</li>
 *   <li>the legacy cardforge URL.</li>
 * </ol>
 * The additional printing lookup avoids hammering api.scryfall.com for variant arts
 * that are missing from the local CDN UUID cache.
 */
public class GuiDownloadFilteredCardImages extends GuiDownloadService {

    private final Predicate<PaperCard> filter;

    public GuiDownloadFilteredCardImages(Predicate<PaperCard> filter) {
        this.filter = filter;
    }

    @Override
    public String getTitle() {
        return "Download Card Images";
    }

    @Override
    protected Map<String, String> getNeededFiles() throws UnsupportedEncodingException {
        final Map<String, String> downloads = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        final List<PaperCard> matches = new ArrayList<>();
        final TreeSet<String> scryfallSetCodes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (final PaperCard c : Iterables.concat(
                FModel.getMagicDb().getCommonCards().getAllCards(),
                FModel.getMagicDb().getVariantCards().getAllCards())) {

            if (!filter.test(c)) { continue; }

            final String setCode3 = c.getEdition();
            if (StringUtils.isBlank(setCode3) || CardEdition.UNKNOWN_CODE.equals(setCode3)) { continue; }

            matches.add(c);
            CardEdition edition = StaticData.instance().getEditions().get(setCode3);
            if (edition != null && !StringUtils.isBlank(edition.getScryfallCode())) {
                scryfallSetCodes.add(edition.getScryfallCode());
            }
        }

        // Warm the CDN UUID cache for every needed set before resolving URLs below, so this run
        // prefers the unthrottled CDN path over the rate-limited Scryfall API. Skip sets already
        // cached (e.g. by a prior bulk-data sync).
        final List<String> needSync = new ArrayList<>();
        for (String setCode : scryfallSetCodes) {
            if (!CdnUuidCache.isSetCached(setCode)) {
                needSync.add(setCode);
            }
        }
        if (needSync.isEmpty() && !scryfallSetCodes.isEmpty()) {
            reportStatus("All " + scryfallSetCodes.size() + " needed sets already cached.");
        } else if (!needSync.isEmpty()) {
            int alreadyCached = scryfallSetCodes.size() - needSync.size();
            if (alreadyCached > 0) {
                reportStatus(alreadyCached + "/" + scryfallSetCodes.size() + " sets already cached; syncing the remaining " + needSync.size() + "...");
            }
        }

        // Syncing sets one at a time against the rate-limited Scryfall API is fine for a handful
        // of sets, but with a cold cache (dozens/hundreds of missing sets) it can take minutes of
        // sequential requests while barely updating the UI. Past a threshold, do it in one shot
        // via the bulk-data export instead -- the same mechanism as the "Descarga masiva" button.
        final int BULK_SYNC_THRESHOLD = 15;
        if (needSync.size() > BULK_SYNC_THRESHOLD) {
            reportStatus(needSync.size() + " sets need syncing; using bulk data sync instead of "
                    + "one-by-one requests...");
            int setCount = ScryfallBulkDataSync.sync(ScryfallBulkDataSync.BULK_TYPE_DEFAULT_CARDS, null,
                    (message, fraction) -> reportStatus(message), () -> cancel);
            if (setCount < 0 && !cancel) {
                reportStatus("Bulk sync failed; falling back to per-set sync...");
                syncSetsOneByOne(needSync);
            }
        } else {
            syncSetsOneByOne(needSync);
        }

        for (final PaperCard c : matches) {
            addIfMissing(c, "", downloads);
            if (c.hasBackFace()) {
                addIfMissing(c, "back", downloads);
            }
        }
        return downloads;
    }

    private void syncSetsOneByOne(List<String> needSync) throws UnsupportedEncodingException {
        int setIndex = 0;
        for (String setCode : needSync) {
            if (cancel) break;
            setIndex++;
            reportStatus("Syncing card data from an online source: set " + setIndex + "/" + needSync.size()
                    + " (" + setCode.toUpperCase() + ")...");
            ScryfallRateLimiter.awaitCooldownCleared(() -> cancel, this::reportStatus);
            if (cancel) break;
            ScryfallSetSync.sync(setCode);
        }
    }

    // -------------------------------------------------------------------------

    private static void addIfMissing(PaperCard c, String face, Map<String, String> downloads) {
        final String imageKey = ImageUtil.getImageKey(c, face, true);
        if (imageKey == null) return;

        final File destFull = new File(ForgeConstants.CACHE_CARD_PICS_DIR, imageKey + ".jpg");
        final String fbKey = TextUtil.fastReplace(imageKey, ".full", ".fullborder") +
                             (!imageKey.contains(".full") ? ".fullborder" : "");
        final File destFb = new File(ForgeConstants.CACHE_CARD_PICS_DIR, fbKey + ".jpg");

        if (destFull.exists() || destFb.exists()) return;
        if (downloads.containsKey(destFull.getAbsolutePath())) return;
        if (downloads.containsKey(destFb.getAbsolutePath())) return;

        final String url = buildUrl(c, face);
        if (url == null) return;

        downloads.put(destFb.getAbsolutePath(), url);
    }

    /**
     * Best available download URL for one face.
     * CDN for the current printing, then CDN for another printing with the same name,
     * then the Scryfall API, then cardforge.
     */
    private static String buildUrl(PaperCard c, String face) {
        String cdnUrl = buildCdnUrl(c, face);
        if (cdnUrl != null) return cdnUrl;

        for (PaperCard candidate : StaticData.instance().getCommonCards().getAllCards(c)) {
            if (candidate == null || candidate.getEdition() == null) continue;
            if (candidate.getEdition().equalsIgnoreCase(c.getEdition())) continue;
            cdnUrl = buildCdnUrl(candidate, face);
            if (cdnUrl != null) return cdnUrl;
        }

        return buildFallbackUrl(c, face);
    }

    /** Current printing's CDN URL, or null if it is not in the local UUID cache. */
    private static String buildCdnUrl(PaperCard c, String face) {
        final String collectorNum = c.getCollectorNumber();
        if (IPaperCard.NO_COLLECTOR_NUMBER.equals(collectorNum)
                || "0".equals(collectorNum)
                || StringUtils.isBlank(collectorNum)) {
            return null;
        }

        final CardEdition edition = StaticData.instance().getEditions().get(c.getEdition());
        if (edition == null) return null;
        final String scryfallCode = edition.getScryfallCode();
        if (StringUtils.isBlank(scryfallCode)) return null;

        final String preferredLang = FModel.getPreferences().getPref(ForgePreferences.FPref.UI_CARD_DOWNLOAD_LANG);
        final String langCode = CdnUuidCache.resolvePreferredLangCode(
                preferredLang, scryfallCode, collectorNum, edition.getCardsLangCode());
        return CdnUuidCache.getCdnUrlIfCached(scryfallCode, collectorNum, langCode, face, "normal");
    }

    /** Scryfall API, then cardforge, for the requested printing. */
    private static String buildFallbackUrl(PaperCard c, String face) {
        final String collectorNum = c.getCollectorNumber();
        final boolean hasCollectorNum = !IPaperCard.NO_COLLECTOR_NUMBER.equals(collectorNum)
                && !"0".equals(collectorNum)
                && !StringUtils.isBlank(collectorNum);

        final CardEdition edition = hasCollectorNum
                ? StaticData.instance().getEditions().get(c.getEdition()) : null;
        final String scryfallCode = (edition != null) ? edition.getScryfallCode() : null;
        final boolean hasScryfallCode = !StringUtils.isBlank(scryfallCode);
        final String preferredLang = FModel.getPreferences().getPref(ForgePreferences.FPref.UI_CARD_DOWNLOAD_LANG);
        final String langCode = (edition != null && hasScryfallCode)
                ? CdnUuidCache.resolvePreferredLangCode(preferredLang, scryfallCode, collectorNum, edition.getCardsLangCode())
                : null;

        if (hasCollectorNum && edition != null && hasScryfallCode) {
            String apiPath = ImageUtil.getScryfallDownloadUrl(
                    c, face, scryfallCode, langCode, false);
            if (apiPath != null) return ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD + apiPath;
        }

        String cardforgeUrl = ImageUtil.getDownloadUrl(c, face);
        return cardforgeUrl != null ? ForgeConstants.URL_PIC_DOWNLOAD + cardforgeUrl : null;
    }
}
