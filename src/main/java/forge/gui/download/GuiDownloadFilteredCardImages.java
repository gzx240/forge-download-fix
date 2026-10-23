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
 *   <li>the current printing's cached CDN URL in the preferred language,</li>
 *   <li>a cached CDN URL for another printing of the same card name in the same set,</li>
 *   <li>the current printing's cached CDN URL in any other language,</li>
 *   <li>a cached CDN URL for another printing of the same card name in any set,</li>
 *   <li>the rate-limited Scryfall API,</li>
 *   <li>the legacy cardforge URL.</li>
 * </ol>
 * The additional printing lookup avoids hammering api.scryfall.com for variant arts
 * that are missing from the local CDN UUID cache, and keeps a variant art slot filled
 * instead of leaving a blank card behind a silent 404.
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
     * Best available download URL for one face, in decreasing order of preference:
     * the current printing's cached CDN URL, another printing of the same card name in
     * the same set, that same printing in another cached language, another printing of
     * the same card name in any set, the Scryfall API, then cardforge.
     */
    private static String buildUrl(PaperCard c, String face) {
        String url = buildCdnUrl(c, face);
        if (url != null) return url;

        // Variant art slots Forge lists but the CDN does not carry: prefer the base art of
        // the same set, so the card keeps its own set frame and the preferred language
        // instead of showing another language's card face.
        url = buildCdnUrlForOtherPrinting(c, face, c.getEdition());
        if (url != null) return url;

        // No sibling printing in this set: the art exists, only the preferred-language
        // UUID is missing from the cache (e.g. ACR collector 272 is cached as grc only,
        // so the en lookup finds nothing and the Scryfall API 404s).
        url = buildCdnUrlInAnyLanguage(c, face);
        if (url != null) return url;

        url = buildCdnUrlForOtherPrinting(c, face, null);
        if (url != null) return url;

        return buildFallbackUrl(c, face);
    }

    /** Current printing's CDN URL in the preferred download language, or null if not cached. */
    private static String buildCdnUrl(PaperCard c, String face) {
        final String collectorNum = lookupCollectorNumber(c);
        final String scryfallCode = lookupScryfallCode(c);
        if (collectorNum == null || scryfallCode == null) return null;

        final CardEdition edition = StaticData.instance().getEditions().get(c.getEdition());
        final String preferredLang = FModel.getPreferences().getPref(ForgePreferences.FPref.UI_CARD_DOWNLOAD_LANG);
        final String langCode = CdnUuidCache.resolvePreferredLangCode(
                preferredLang, scryfallCode, collectorNum, edition.getCardsLangCode());
        return CdnUuidCache.getCdnUrlIfCached(scryfallCode, collectorNum, langCode, face, "normal");
    }

    /** Current printing's CDN URL in whichever language the cache does hold, or null. */
    private static String buildCdnUrlInAnyLanguage(PaperCard c, String face) {
        final String collectorNum = lookupCollectorNumber(c);
        final String scryfallCode = lookupScryfallCode(c);
        if (collectorNum == null || scryfallCode == null) return null;

        for (String langCode : CdnUuidCache.LANGUAGE_CODES) {
            final String url = CdnUuidCache.getCdnUrlIfCached(scryfallCode, collectorNum, langCode, face, "normal");
            if (url != null) return url;
        }
        return null;
    }

    /**
     * CDN URL for another printing of the same card name, restricted to one set when
     * {@code onlyEdition} is non-null. Returns null when no sibling printing is cached.
     */
    private static String buildCdnUrlForOtherPrinting(PaperCard c, String face, String onlyEdition) {
        for (PaperCard candidate : StaticData.instance().getCommonCards().getAllCards(c)) {
            if (candidate == null || candidate.getEdition() == null) continue;
            if (isSamePrinting(candidate, c)) continue;
            if (onlyEdition != null && !candidate.getEdition().equalsIgnoreCase(onlyEdition)) continue;
            final String url = buildCdnUrl(candidate, face);
            if (url != null) return url;
        }
        return null;
    }

    private static boolean isSamePrinting(PaperCard a, PaperCard b) {
        return a.getArtIndex() == b.getArtIndex()
                && sameText(a.getEdition(), b.getEdition())
                && sameText(a.getCollectorNumber(), b.getCollectorNumber());
    }

    /** Null-safe case-insensitive comparison. */
    private static boolean sameText(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    /** Collector number usable for a printing lookup, or null when the card has none. */
    private static String lookupCollectorNumber(PaperCard c) {
        final String collectorNum = c.getCollectorNumber();
        if (StringUtils.isBlank(collectorNum)
                || IPaperCard.NO_COLLECTOR_NUMBER.equals(collectorNum)
                || "0".equals(collectorNum)) {
            return null;
        }
        return collectorNum;
    }

    /** Scryfall set code for the card's edition, or null when the edition has none. */
    private static String lookupScryfallCode(PaperCard c) {
        final CardEdition edition = StaticData.instance().getEditions().get(c.getEdition());
        if (edition == null) return null;
        final String scryfallCode = edition.getScryfallCode();
        return StringUtils.isBlank(scryfallCode) ? null : scryfallCode;
    }

    /** Scryfall API, then cardforge, for the requested printing. */
    private static String buildFallbackUrl(PaperCard c, String face) {
        final String collectorNum = lookupCollectorNumber(c);
        final String scryfallCode = lookupScryfallCode(c);
        final boolean hasCollectorNum = collectorNum != null;
        final boolean hasScryfallCode = scryfallCode != null;

        final CardEdition edition = StaticData.instance().getEditions().get(c.getEdition());
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