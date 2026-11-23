package com.gregoire.thorstandings;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Set;

/**
 * Team logos packed in the APK (app/src/main/assets/teams/<league>/<name>.png), so they show
 * without Wi-Fi. Matched by the team's full name ("Montreal Canadiens" -> montrealcanadiens).
 * Teams not in the pack keep ESPN's logo.
 */
final class OfflineLogos {
    private static final Set<String> KEYS = new HashSet<>();

    static {
        add("mlb", "arizonadiamondbacks athletics atlantabraves baltimoreorioles bostonredsox chicagocubs chicagowhitesox cincinnatireds clevelandguardians coloradorockies detroittigers houstonastros kansascityroyals losangelesangels losangelesdodgers miamimarlins milwaukeebrewers minnesotatwins newyorkmets newyorkyankees philadelphiaphillies pittsburghpirates sandiegopadres sanfranciscogiants seattlemariners stlouiscardinals tampabayrays texasrangers torontobluejays washingtonnationals");
        add("mls", "atlantaunitedfc austinfc cfmontreal charlottefc chicagofirefc coloradorapids columbuscrew dcunited fccincinnati fcdallas houstondynamofc intermiamicf lafc lagalaxy minnesotaunitedfc nashvillesc newenglandrevolution newyorkcityfc newyorkredbulls orlandocitysc philadelphiaunion portlandtimbers realsaltlake sanjoseearthquakes seattlesoundersfc sportingkansascity stlouiscitysc torontofc vancouverwhitecapsfc");
        add("nba", "atlantahawks bostonceltics brooklynnets charlottehornets chicagobulls clevelandcavaliers dallasmavericks denvernuggets detroitpistons goldenstatewarriors houstonrockets indianapacers laclippers losangeleslakers memphisgrizzlies miamiheat milwaukeebucks minnesotatimberwolves neworleanspelicans newyorkknicks oklahomacitythunder orlandomagic philadelphia76ers phoenixsuns portlandtrailblazers sacramentokings sanantoniospurs torontoraptors utahjazz washingtonwizards");
        add("nfl", "arizonacardinals atlantafalcons baltimoreravens buffalobills carolinapanthers chicagobears cincinnatibengals clevelandbrowns dallascowboys denverbroncos detroitlions greenbaypackers houstontexans indianapoliscolts jacksonvillejaguars kansascitychiefs lasvegasraiders losangeleschargers losangelesrams miamidolphins minnesotavikings newenglandpatriots neworleanssaints newyorkgiants newyorkjets philadelphiaeagles pittsburghsteelers sanfrancisco49ers seattleseahawks tampabaybuccaneers tennesseetitans washingtoncommanders");
        add("nhl", "anaheimducks bostonbruins buffalosabres calgaryflames carolinahurricanes chicagoblackhawks coloradoavalanche columbusbluejackets dallasstars detroitredwings edmontonoilers floridapanthers losangeleskings minnesotawild montrealcanadiens nashvillepredators newjerseydevils newyorkislanders newyorkrangers ottawasenators philadelphiaflyers pittsburghpenguins sanjosesharks seattlekraken stlouisblues tampabaylightning torontomapleleafs vancouvercanucks vegasgoldenknights washingtoncapitals winnipegjets");
    }

    private static void add(String lg, String names) {
        for (String n : names.split(" ")) KEYS.add(lg + "/" + n);
    }

    private static String folder(String leaguePath) {
        switch (leaguePath == null ? "" : leaguePath) {
            case "hockey/nhl": return "nhl";
            case "football/nfl": return "nfl";
            case "basketball/nba": return "nba";
            case "baseball/mlb": return "mlb";
            case "soccer/usa.1": return "mls";
            default: return null;
        }
    }

    static String key(String fullName) {
        if (fullName == null) return "";
        String s = Normalizer.normalize(fullName, Normalizer.Form.NFD).toLowerCase(java.util.Locale.US);
        return s.replaceAll("[^a-z0-9]", "");
    }

    /** "asset:teams/nhl/montrealcanadiens.png|<espn url>" when packed, else the ESPN url unchanged. */
    static String wrap(String leaguePath, String fullName, String url) {
        String lg = folder(leaguePath);
        if (lg == null) return url;
        String k = lg + "/" + key(fullName);
        if (!KEYS.contains(k)) return url;
        String a = "asset:teams/" + k + ".png";
        return url == null || url.isEmpty() ? a : a + "|" + url;
    }

    /** Packed league logo (NHL, NFL, NBA, MLB, MLS), or null. */
    static String league(String leaguePath) {
        String lg = folder(leaguePath);
        return lg == null ? null : "asset:league/" + lg + ".png";
    }
}
