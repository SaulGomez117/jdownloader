//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.decrypter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.appwork.uio.UIOManager;
import org.appwork.utils.StringUtils;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterException;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;
import jd.utils.JDUtilities;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "pinterest.com" }, urls = { "https?://(?:(?:www|[a-z]{2})\\.)?pinterest\\.(?:com|de|fr)/(pin/[A-Za-z0-9\\-_]+/|[^/]+/[^/]+/(?:[^/]+/)?)" })
public class PinterestComDecrypter extends PluginForDecrypt {
    public PinterestComDecrypter(PluginWrapper wrapper) {
        super(wrapper);
    }

    private static final String     unsupported_urls                             = "https?://(?:www\\.)?pinterest\\.[A-Za-z]+/(business/create/|android\\-app:/.+|ios\\-app:/.+|categories/.+|resource/.+|explore/.+)";
    private static final boolean    force_api_usage                              = true;
    private ArrayList<DownloadLink> decryptedLinks                               = null;
    private ArrayList<String>       dupeList                                     = new ArrayList<String>();
    private String                  parameter                                    = null;
    private String                  source_url                                   = null;
    private String                  board_id                                     = null;
    private String                  linkpart;
    /* Reset this after every function use e.g. crawlSections --> Reset --> crawlBoardPINs */
    private int                     numberof_pins_decrypted_via_current_function = 0;
    private FilePackage             fp                                           = null;
    private boolean                 loggedIN                                     = false;
    private boolean                 enable_description_inside_filenames          = jd.plugins.hoster.PinterestCom.defaultENABLE_DESCRIPTION_IN_FILENAMES;
    private boolean                 enable_crawl_alternative_URL                 = jd.plugins.hoster.PinterestCom.defaultENABLE_CRAWL_ALTERNATIVE_SOURCE_URLS;
    private static final int        max_entries_per_page_free                    = 25;

    @SuppressWarnings({ "unchecked", "deprecation" })
    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        br = new Browser();
        decryptedLinks = new ArrayList<DownloadLink>();
        final PluginForHost hostPlugin = JDUtilities.getPluginForHost(this.getHost());
        enable_description_inside_filenames = hostPlugin.getPluginConfig().getBooleanProperty(jd.plugins.hoster.PinterestCom.ENABLE_DESCRIPTION_IN_FILENAMES, enable_description_inside_filenames);
        enable_crawl_alternative_URL = hostPlugin.getPluginConfig().getBooleanProperty(jd.plugins.hoster.PinterestCom.ENABLE_CRAWL_ALTERNATIVE_SOURCE_URLS, jd.plugins.hoster.PinterestCom.defaultENABLE_CRAWL_ALTERNATIVE_SOURCE_URLS);
        /* Correct link - remove country related language-subdomains (e.g. 'es.pinterest.com'). */
        linkpart = new Regex(param.toString(), "pinterest\\.[^/]+/(.+)").getMatch(0);
        parameter = "https://www.pinterest.com/" + linkpart;
        source_url = new Regex(parameter, "pinterest\\.com(/.+)").getMatch(0);
        fp = FilePackage.getInstance();
        br.setFollowRedirects(true);
        if (parameter.matches(unsupported_urls)) {
            decryptedLinks.add(getOffline(parameter));
            return decryptedLinks;
        }
        /* Sometimes html can be very big */
        br.setLoadLimit(br.getLoadLimit() * 4);
        loggedIN = getUserLogin(false);
        if (new Regex(this.parameter, ".+/pin/.+").matches()) {
            parseSinglePIN();
        } else {
            /*
             * In case the user wants to add a specific section, we have to get to the section overview --> Find sectionID --> Finally crawl
             * section PINs
             */
            final String targetSection = new Regex(this.parameter, "https?://[^/]+/[^/]+/[^/]+/([^/]+)").getMatch(0);
            if (targetSection != null) {
                /* Remove targetSection from URL as we cannot use it in this way. */
                parameter = parameter.replace(targetSection + "/", "");
            }
            br.getPage(parameter);
            if (br.getHttpConnection().getResponseCode() == 404) {
                decryptedLinks.add(getOffline(parameter));
                return decryptedLinks;
            }
            // referrer should always be of the first request!
            final Browser ajax = br.cloneBrowser();
            prepAPIBRCrawler(ajax);
            /* This is a prominent point of failure! */
            final String json_source_html = getJsonSourceFromHTML(this.br);
            board_id = getBoardID(json_source_html);
            final LinkedHashMap<String, Object> json_root = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(json_source_html);
            boolean foundTargetSection = false;
            final String section_count = PluginJSonUtils.getJson(json_source_html, "section_count");
            if (section_count != null && Integer.parseInt(section_count) > 0 && loggedIN) {
                /* Crawl sections - only available when loggedIN (2017-11-22) */
                foundTargetSection = this.crawlSections(ajax.cloneBrowser(), targetSection);
            }
            /*
             * Now find all the PINs that are not in any sections (it may happen that we already have everything at this stage!) Only
             * decrypt these leftover PINs if either the user did not want to have a specified section only or if he wanted to have a
             * specified section only but it could not be found --> Crawl everything
             */
            if (!foundTargetSection) {
                this.crawlBoardPINs(ajax, json_root, json_source_html, param);
            }
        }
        return decryptedLinks;
    }

    private void parseSinglePIN() throws Exception {
        final DownloadLink singlePIN = this.createPINDownloadUrl(this.parameter);
        final String pin_id = jd.plugins.hoster.PinterestCom.getPinID(this.parameter);
        if (enable_crawl_alternative_URL) {
            try {
                final LinkedHashMap<String, Object> pinMap = findPINMap(this.br, this.loggedIN, this.parameter, null, null, null);
                setInfoOnDownloadLink(this.br, singlePIN, pinMap, null, loggedIN);
                final String externalURL = getAlternativeExternalURLInPINMap(pinMap);
                if (externalURL != null) {
                    this.decryptedLinks.add(this.createDownloadlink(externalURL));
                }
                fp.setName(singlePIN.getRawName());
            } catch (final PluginException e) {
                /* Offline */
                singlePIN.setAvailable(false);
                /* Fallback */
                fp.setName(pin_id);
            }
        } else {
            fp.setName(pin_id);
        }
        this.decryptedLinks.add(singlePIN);
        fp.addLinks(this.decryptedLinks);
    }

    public static void setInfoOnDownloadLink(final Browser br, final DownloadLink dl, final LinkedHashMap<String, Object> pinMap, String directlink, final boolean loggedIN) {
        final String pin_id = jd.plugins.hoster.PinterestCom.getPinID(dl.getDownloadURL());
        String filename = null;
        final String description;
        if (pinMap != null) {
            if (StringUtils.isEmpty(directlink)) {
                directlink = getDirectlinkFromPINMap(pinMap);
            }
            final LinkedHashMap<String, Object> data = pinMap.containsKey("data") ? (LinkedHashMap<String, Object>) pinMap.get("data") : pinMap;
            if (loggedIN) {
                try {
                    final LinkedHashMap<String, Object> page_info = (LinkedHashMap<String, Object>) pinMap.get("page_info");
                    filename = (String) page_info.get("title");
                } catch (final Throwable e) {
                }
            } else if (br.getURL().contains(pin_id)) {
                /* Try to get title from html but only if our browser just accessed the PIN we're crawling! */
                filename = br.getRegex("<title>([^<>\"]*?)</title>").getMatch(0);
            }
            if (StringUtils.isEmpty(filename)) {
                filename = (String) data.get("title");
            }
            if (StringUtils.isEmpty(filename)) {
                /* Fallback */
                filename = pin_id;
            } else {
                filename = Encoding.htmlDecode(filename).trim();
                filename = pin_id + "_" + filename;
            }
            description = (String) data.get("description");
        } else {
            filename = dl.getFinalFileName();
            if (filename == null) {
                filename = pin_id;
            }
            description = jd.plugins.hoster.PinterestCom.getPictureDescription(dl);
        }
        final String ext;
        if (!StringUtils.isEmpty(directlink)) {
            ext = getFileNameExtensionFromString(directlink, ".jpg");
        } else {
            ext = ".jpg";
        }
        final PluginForHost hostPlugin = JDUtilities.getPluginForHost(dl.getHost());
        if (hostPlugin.getPluginConfig().getBooleanProperty(jd.plugins.hoster.PinterestCom.ENABLE_DESCRIPTION_IN_FILENAMES, jd.plugins.hoster.PinterestCom.defaultENABLE_DESCRIPTION_IN_FILENAMES) && description != null) {
            filename += "_" + description;
        }
        if (!StringUtils.isEmpty(description) && dl.getComment() == null) {
            dl.setComment(description);
        }
        if (!filename.endsWith(ext)) {
            filename += ext;
        }
        if (directlink != null) {
            dl.setFinalFileName(filename);
            dl.setProperty("free_directlink", directlink);
        } else {
            dl.setName(filename);
        }
        dl.setLinkID(jd.plugins.hoster.PinterestCom.getLinkidForInternalDuplicateCheck(dl.getDownloadURL(), directlink));
        dl.setAvailable(true);
    }

    public static LinkedHashMap<String, Object> findPINMap(final Browser br, final boolean loggedIN, final String contentURL, final String source_url, final String boardid, final String username) throws Exception {
        final String pin_id = jd.plugins.hoster.PinterestCom.getPinID(contentURL);
        LinkedHashMap<String, Object> pinMap = null;
        final ArrayList<Object> resource_data_cache;
        if (loggedIN && source_url != null && boardid != null && username != null) {
            String pin_ressource_url = "http://www.pinterest.com/resource/PinResource/get/?source_url=";
            String options = "/pin/%s/&data={\"options\":{\"field_set_key\":\"detailed\",\"link_selection\":true,\"fetch_visual_search_objects\":true,\"id\":\"%s\"},\"context\":{},\"module\":{\"name\":\"CloseupContent\",\"options\":{\"unauth_pin_closeup\":false}},\"render_type\":1}&module_path=App()>BoardPage(resource=BoardResource(username=amazvicki,+slug=))>Grid(resource=BoardFeedResource(board_id=%s,+board_url=%s,+page_size=null,+prepend=true,+access=,+board_layout=default))>GridItems(resource=BoardFeedResource(board_id=%s,+board_url=%s,+page_size=null,+prepend=true,+access=,+board_layout=default))>Pin(show_pinner=false,+show_pinned_from=true,+show_board=false,+squish_giraffe_pins=false,+component_type=0,+resource=PinResource(id=%s))";
            options = String.format(options, pin_id, pin_id, username, username, boardid, source_url, boardid, source_url, pin_id);
            options = options.replace("/", "%2F");
            // options = Encoding.urlEncode(options);
            pin_ressource_url += options;
            pin_ressource_url += "&_=" + System.currentTimeMillis();
            prepAPIBR(br);
            br.getPage(pin_ressource_url);
            if (jd.plugins.hoster.PinterestCom.isOffline(br, pin_id)) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(br.toString());
            resource_data_cache = (ArrayList) entries.get("resource_data_cache");
        } else {
            br.getPage(contentURL);
            if (jd.plugins.hoster.PinterestCom.isOffline(br, pin_id)) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            LinkedHashMap<String, Object> entries = null;
            /*
             * Site actually contains similar json compared to API --> Grab that and get the final link via that as it is not always present
             * in the normal html code.
             */
            String json = br.getRegex("P\\.(?:start\\.start|main\\.start)\\((.*?)\\);\n").getMatch(0);
            if (json == null) {
                json = br.getRegex("P\\.startArgs = (.*?);\n").getMatch(0);
            }
            if (json != null) {
                /* Website json */
                entries = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(json);
                resource_data_cache = (ArrayList) entries.get("resourceDataCache");
            } else {
                /* API json e.g. needed: https://www.pinterest.com/pin/104497653832270636/ */
                prepAPIBR(br);
                final String pin_json_url = "https://www.pinterest.com/resource/PinResource/get/?source_url=%2Fpin%2F" + pin_id + "%2F&data=%7B%22options%22%3A%7B%22field_set_key%22%3A%22detailed%22%2C%22ptrf%22%3Anull%2C%22fetch_visual_search_objects%22%3Atrue%2C%22id%22%3A%22" + pin_id + "%22%7D%2C%22context%22%3A%7B%7D%7D&module_path=Pin(show_pinner%3Dtrue%2C+show_board%3Dtrue%2C+is_original_pin_in_related_pins_grid%3Dtrue)&_=" + System.currentTimeMillis();
                br.getPage(pin_json_url);
                json = br.toString();
                entries = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(json);
                resource_data_cache = (ArrayList) entries.get("resource_data_cache");
            }
        }
        final boolean grabThis;
        if (resource_data_cache.size() == 1) {
            // logger.info("resource_data_cache contains only 1 item --> This should be the one, we want");
            grabThis = true;
        } else {
            // logger.info("resource_data_cache contains multiple item --> We'll have to find the correct one");
            grabThis = false;
        }
        for (final Object resource_object : resource_data_cache) {
            final LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>) resource_object;
            final String this_pin_id = (String) JavaScriptEngineFactory.walkJson(entries, "data/id");
            final boolean pins_matched = this_pin_id.equals(pin_id);
            if (pins_matched || grabThis) {
                /* We've reached our goal */
                pinMap = entries;
                break;
            }
        }
        return pinMap;
    }

    /** Returns highest resolution image URL inside given PIN Map. */
    public static String getDirectlinkFromPINMap(final LinkedHashMap<String, Object> pinMap) {
        if (pinMap == null) {
            return null;
        }
        String directlink = null;
        try {
            directlink = (String) JavaScriptEngineFactory.walkJson(pinMap, "data/images/orig/url");
        } catch (final Throwable e) {
        }
        return directlink;
    }

    /** Returns e.g. an alternative, probably higher quality imgur.com URL to the same image which we have as Pinterest PIN here. */
    private String getAlternativeExternalURLInPINMap(final LinkedHashMap<String, Object> pinMap) {
        String externalURL = null;
        try {
            String path;
            if (pinMap.containsKey("data")) {
                path = "data/rich_metadata/url";
            } else {
                path = "rich_metadata/url";
            }
            externalURL = (String) JavaScriptEngineFactory.walkJson(pinMap, path);
        } catch (final Throwable e) {
        }
        return externalURL;
    }

    /**
     * @return: true: target section was found and only this will be crawler false: failed to find target section - in this case we should
     *          crawl everything we find
     */
    private boolean crawlSections(final Browser ajax, final String targetSectionTitle) throws Exception {
        final String username_and_boardname = new Regex(this.parameter, "https?://[^/]+/(.+)/").getMatch(0).replace("/", " - ");
        ajax.getPage("/resource/BoardSectionsResource/get/?source_url=" + Encoding.urlEncode(source_url) + "&data=%7B%22options%22%3A%7B%22board_id%22%3A%22" + board_id + "%22%7D%2C%22context%22%3A%7B%7D%7D&_=" + System.currentTimeMillis());
        LinkedHashMap<String, Object> json_root = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(ajax.toString());
        final ArrayList<Object> sections = (ArrayList) JavaScriptEngineFactory.walkJson(json_root, "resource_response/data");
        boolean foundTargetSection = false;
        int sectionCounter = 1;
        for (final Object sectionO : sections) {
            if (this.isAbort()) {
                break;
            }
            logger.info("Crawling section " + sectionCounter + " of " + sections.size());
            LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>) sectionO;
            final String section_title = (String) entries.get("title");
            final long section_total_pin_count = JavaScriptEngineFactory.toLong(entries.get("pin_count"), 0);
            final String section_ID = (String) entries.get("id");
            if (StringUtils.isEmpty(section_title) || section_ID == null || section_total_pin_count == 0) {
                /* Skip invalid entries and empty sections */
                continue;
            }
            fp.setName(username_and_boardname + " - " + section_title);
            if (targetSectionTitle != null && !section_title.equalsIgnoreCase(targetSectionTitle)) {
                logger.info("User wants only a specific section --> Skipping unwanted sections");
                continue;
            } else if (targetSectionTitle != null && section_title.equalsIgnoreCase(targetSectionTitle)) {
                logger.info("User wants only a specific section --> Found that");
                foundTargetSection = true;
                /*  */
                decryptedLinks.clear();
            }
            int decryptedPinCount = 0;
            int stepCount = 1;
            String bookmarks = "";
            // final String url_section = "https://www.pinterest.com/" + source_url + section_title + "/";
            ajax.getPage("/resource/BoardSectionPinsResource/get/?source_url=" + Encoding.urlEncode(source_url) + "&data=%7B%22options%22%3A%7B%22section_id%22%3A%22" + section_ID + "%22%2C%22prepend%22%3Afalse%2C%22page_size%22%3A" + max_entries_per_page_free + "%7D%2C%22context%22%3A%7B%7D%7D&_=" + System.currentTimeMillis());
            do {
                if (this.isAbort()) {
                    break;
                }
                logger.info("Step: " + stepCount);
                if (stepCount > 0) {
                    ajax.getPage("/resource/BoardSectionPinsResource/get/?source_url=" + Encoding.urlEncode(source_url) + "&data=%7B%22options%22%3A%7B%22bookmarks%22%3A%5B%22" + bookmarks + "%22%5D%2C%22section_id%22%3A%22" + section_ID + "%22%2C%22prepend%22%3Afalse%2C%22page_size%22%3A" + max_entries_per_page_free + "%7D%2C%22context%22%3A%7B%7D%7D&_=" + System.currentTimeMillis());
                }
                json_root = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(ajax.toString());
                bookmarks = (String) JavaScriptEngineFactory.walkJson(json_root, "resource/options/bookmarks/{0}");
                final ArrayList<Object> pins = (ArrayList) JavaScriptEngineFactory.walkJson(json_root, "resource_response/data");
                for (final Object pinO : pins) {
                    entries = (LinkedHashMap<String, Object>) pinO;
                    if (!proccessLinkedHashMap(entries, board_id, source_url)) {
                        break;
                    }
                    decryptedPinCount++;
                }
                stepCount++;
                if (bookmarks == null || bookmarks.equals("-end-")) {
                    /* Looks as if we've reached the end */
                    break;
                }
            } while (decryptedPinCount < section_total_pin_count);
            if (foundTargetSection) {
                /* We got what we wanted --> Stop here */
                break;
            }
            sectionCounter++;
        }
        if (targetSectionTitle != null && foundTargetSection) {
            logger.info("Found targetSection and only added it");
        } else if (targetSectionTitle != null && !foundTargetSection) {
            logger.info("Failed to find targetSection --> Added ALL sections");
        } else {
            logger.info("Added ALL sections");
        }
        return foundTargetSection;
    }

    private void crawlBoardPINs(final Browser ajax, LinkedHashMap<String, Object> json_root, String json_source_for_crawl_process, final CryptedLink param) throws Exception {
        numberof_pins_decrypted_via_current_function = 0;
        /* Find- and set PackageName */
        String fpName = br.getRegex("class=\"boardName\">([^<>]*?)<").getMatch(0);
        if (fpName == null) {
            fpName = linkpart.replace("/", "_");
        }
        fp.setName(Encoding.htmlDecode(fpName.trim()));
        /* Find out how many PINs we have to crawl. */
        String numberof_pins_str = br.getRegex("class=\"value\">(\\d+(?:\\.\\d+)?)</span> <span class=\"label\">Pins</span>").getMatch(0);
        if (numberof_pins_str == null) {
            numberof_pins_str = br.getRegex("class=\'value\'>(\\d+(?:\\.\\d+)?)</span> <span class=\'label\'>Pins</span>").getMatch(0);
        }
        if (numberof_pins_str == null) {
            numberof_pins_str = br.getRegex("name=\"pinterestapp:pins\" content=\"(\\d+)\"").getMatch(0);
        }
        if (numberof_pins_str == null) {
            numberof_pins_str = Long.toString(JavaScriptEngineFactory.toLong(JavaScriptEngineFactory.walkJson(json_root, "resource_response/data/pin_count"), 0));
            if (numberof_pins_str == null || numberof_pins_str.equals("0")) {
                /* Wider attempt */
                numberof_pins_str = PluginJSonUtils.getJson(json_source_for_crawl_process, "pin_count");
            }
        }
        if (numberof_pins_str == null) {
            logger.info("numberof_pins_str = null --> Offline or not a PIN site");
            decryptedLinks.add(this.createOfflinelink(parameter));
            return;
        }
        final long number_of_decrypted_pins_in_sections = decryptedLinks.size();
        long numberof_pins = Long.parseLong(numberof_pins_str.replace(".", ""));
        if (numberof_pins == 0 && number_of_decrypted_pins_in_sections == 0) {
            decryptedLinks.add(getOffline(parameter));
            return;
        } else if (numberof_pins == 0 && number_of_decrypted_pins_in_sections > 0) {
            /* Do not add offline url in this case as we have found some PINs inside sections before */
            return;
        }
        if (number_of_decrypted_pins_in_sections > 0 && numberof_pins > number_of_decrypted_pins_in_sections) {
            /*
             * We only get the total count but it may happen that some of these PINs are located inside sections which have already been
             * crawled before --> Calculate the correct number of remaining PINs
             */
            logger.info("Total number of PINs: " + numberof_pins);
            logger.info("Total number of PINs inside sections: " + number_of_decrypted_pins_in_sections);
            numberof_pins = numberof_pins - number_of_decrypted_pins_in_sections;
            logger.info("Total number of PINs outside sections (to be crawled now): " + numberof_pins);
        }
        if (json_source_for_crawl_process == null && force_api_usage) {
            // error handling, this has to be always not null!
            logger.warning("json_source = null");
            throw new DecrypterException("Decrypter broken");
        }
        if (loggedIN || force_api_usage) {
            String nextbookmark = null;
            /* First, get the first 25 pictures from their site. */
            int i = 0;
            do {
                try {
                    if (this.isAbort()) {
                        logger.info("Decryption aborted by user: " + parameter);
                        return;
                    }
                    /*
                     * 2017-07-19: First round does not necessarily have to contain items but we look for them anyways - then we'll enter
                     * this ajax mode.
                     */
                    if (i > 0) {
                        /*
                         * 2017-07-19: When loggedIN, first batch of results gets loaded via ajax as well so we will only abort if we still
                         * got 0 items after 2 loops.
                         */
                        if (i > 1 && dupeList.isEmpty()) {
                            /*
                             * 2017-07-18: It can actually happen that according to the website, one or more PIN items are available but
                             * actually nothing is available ...
                             */
                            logger.info("Failed to find any entry - either wrong URL, broken website or (low chance) plugin issue");
                            return;
                        } else if (board_id == null) {
                            logger.warning("board_id = null --> Failed to grab more than the first batch of items");
                            break;
                        }
                        /* not required. */
                        final String module = ""; // "&module_path=App%3ENags%3EUnauthBanner%3EUnauthHomePage%3ESignupForm%3EUserRegister(wall_class%3DdarkWall%2C+container%3Dinspired_banner%2C+show_personalize_field%3Dfalse%2C+next%3Dnull%2C+force_disable_autofocus%3Dnull%2C+is_login_form%3Dnull%2C+show_business_signup%3Dnull%2C+auto_follow%3Dnull%2C+register%3Dtrue)";
                        final String getpage;
                        if (loggedIN) {
                            if (i == 1) {
                                /* 2nd round (first ajax round) --> We do not need nextbookmark */
                                nextbookmark = "";
                            } else if (i > 1 && nextbookmark == null) {
                                /* 3rd round (2nd ajax round) --> We need nextbookmark */
                                logger.info("Failed to find nextbookmark for first / second round --> Cannot grab more items --> Stopping");
                                break;
                            }
                            getpage = "/resource/BoardFeedResource/get/?source_url=" + Encoding.urlEncode(source_url) + "&data=%7B%22options%22%3A%7B%22bookmarks%22%3A%5B%22" + Encoding.urlEncode(nextbookmark) + "%22%5D%2C%22access%22%3A%5B%5D%2C%22board_id%22%3A%22" + board_id + "%22%2C%22board_url%22%3A%22" + Encoding.urlEncode(source_url) + "%22%2C%22field_set_key%22%3A%22react_grid_pin%22%2C%22layout%22%3A%22default%22%2C%22page_size%22%3A" + max_entries_per_page_free + "%7D%2C%22context%22%3A%7B%7D%7D&_=" + System.currentTimeMillis();
                        } else {
                            if (nextbookmark == null) {
                                logger.info("Failed to find nextbookmark for first round --> Cannot grab more items --> Stopping");
                                break;
                            }
                            getpage = "/resource/BoardFeedResource/get/?" + Encoding.urlEncode(source_url) + "%2F&data=%7B%22options%22%3A%7B%22board_id%22%3A%22" + board_id + "%22%2C%22page_size%22%3A" + max_entries_per_page_free + "%2C%22add_vase%22%3Atrue%2C%22bookmarks%22%3A%5B%22" + Encoding.urlEncode(nextbookmark) + "%3D%3D%22%5D%2C%22field_set_key%22%3A%22unauth_react%22%7D%2C%22context%22%3A%7B%7D%7D" + module + "&_=" + System.currentTimeMillis();
                        }
                        int failcounter_http_5034 = 0;
                        /* 2016-11-03: Added retries on HTTP/1.1 503 first byte timeout | HTTP/1.1 504 GATEWAY_TIMEOUT */
                        do {
                            if (this.isAbort()) {
                                logger.info("Decryption aborted by user: " + parameter);
                                return;
                            }
                            if (failcounter_http_5034 > 0) {
                                logger.info("503/504 error retry " + failcounter_http_5034);
                                this.sleep(5000, param);
                            }
                            ajax.getPage(getpage);
                            failcounter_http_5034++;
                        } while ((ajax.getHttpConnection().getResponseCode() == 504 || ajax.getHttpConnection().getResponseCode() == 503) && failcounter_http_5034 <= 4);
                        if (!ajax.getRequest().getHttpConnection().isOK()) {
                            throw new IOException("Invalid responseCode " + ajax.getRequest().getHttpConnection().getResponseCode());
                        }
                        json_source_for_crawl_process = ajax.toString();
                    }
                    json_root = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(json_source_for_crawl_process);
                    LinkedHashMap<String, Object> entries = null;
                    ArrayList<Object> resource_data_list = (ArrayList) json_root.get("resource_data_cache");
                    ArrayList<Object> pin_list = null;
                    if (resource_data_list == null) {
                        /*
                         * Not logged in ? Sometimes needed json is already given in html code! It has minor differences compared to the
                         * API.
                         */
                        resource_data_list = (ArrayList) json_root.get("resourceDataCache");
                    }
                    // new website response (tested without login) -raztoki20160405
                    final LinkedHashMap<String, Object> test = (LinkedHashMap<String, Object>) json_root.get("_dv");
                    // every entry of _dv has what we need + its own cover which we wont bother about.
                    if (test != null && !test.isEmpty()) {
                        for (final Map.Entry<String, Object> entry : test.entrySet()) {
                            if (Long.parseLong(entry.getKey()) == Long.parseLong(board_id)) {
                                // _did within resource_data_list == ids within _dv
                                continue;
                            }
                            final LinkedHashMap<String, Object> single_pinterest_data = (LinkedHashMap<String, Object>) entry.getValue();
                            proccessLinkedHashMap(single_pinterest_data, board_id, source_url);
                        }
                        nextbookmark = (String) JavaScriptEngineFactory.walkJson(resource_data_list, "{1}/resource/options/bookmarks/{0}");
                        logger.info("Decrypted " + dupeList.size() + " of " + numberof_pins + " pins");
                    } else {
                        // json
                        /* Find correct list of PINs */
                        if (resource_data_list != null) {
                            for (Object o : resource_data_list) {
                                entries = (LinkedHashMap<String, Object>) o;
                                o = entries.get("data");
                                if (o != null && o instanceof ArrayList) {
                                    resource_data_list = (ArrayList) o;
                                    if (numberof_pins >= max_entries_per_page_free && resource_data_list.size() != max_entries_per_page_free) {
                                        /*
                                         * If we have more pins then pins per page we should at lest have as much as
                                         * max_entries_per_page_free pins (on the first page)!
                                         */
                                        continue;
                                    }
                                    pin_list = resource_data_list;
                                    break;
                                }
                            }
                        }
                        if (pin_list == null) {
                            /* Final fallback - RegEx the pin-array --> Json parser */
                            String pin_list_json_source;
                            if (i == 0) {
                                /* RegEx json from jsom html from first page */
                                pin_list_json_source = new Regex(json_source_for_crawl_process, "\"board_feed\"\\s*?:\\s*?(\\[.+),\\s*?\"children\"").getMatch(0);
                                if (pin_list_json_source == null) {
                                    pin_list_json_source = new Regex(json_source_for_crawl_process, "\"board_feed\"\\s*?:\\s*?(\\[.+),\\s*?\"options\"").getMatch(0);
                                }
                            } else {
                                /* RegEx json from ajax response > 1 page */
                                pin_list_json_source = new Regex(json_source_for_crawl_process, "\"resource_response\".*?\"data\"\\s*?:\\s*?(\\[.+),\\s*?\"error\"").getMatch(0);
                            }
                            if (pin_list_json_source != null) {
                                pin_list = (ArrayList) JavaScriptEngineFactory.jsonToJavaObject(pin_list_json_source);
                            }
                        }
                        if (pin_list != null) {
                            for (final Object pint : pin_list) {
                                final LinkedHashMap<String, Object> single_pinterest_data = (LinkedHashMap<String, Object>) pint;
                                proccessLinkedHashMap(single_pinterest_data, board_id, source_url);
                            }
                        } else {
                            processPinsKamikaze(json_root, board_id, source_url);
                        }
                        if (decryptedLinks.size() == 0 && i == 0) {
                            logger.info("Found nothing on first run");
                            continue;
                        } else if (decryptedLinks.size() == 0 && i > 2) {
                            /* Multiple cycles but no results --> End?! */
                            logger.info("No PINs found --> We've probably reached the end");
                            break;
                        }
                        nextbookmark = (String) JavaScriptEngineFactory.walkJson(entries, "resource/options/bookmarks/{0}");
                        if (nextbookmark == null || nextbookmark.equalsIgnoreCase("-end-")) {
                            /* Fallback to RegEx */
                            nextbookmark = new Regex(json_source_for_crawl_process, "\"bookmarks\"\\s*?:\\s*?\"([^\"]{6,})\"").getMatch(0);
                        }
                        logger.info("Decrypted " + numberof_pins_decrypted_via_current_function + " of " + numberof_pins + " pins");
                    }
                } finally {
                    i++;
                }
            } while (numberof_pins_decrypted_via_current_function < numberof_pins);
        } else {
            decryptSite();
            if (numberof_pins > max_entries_per_page_free) {
                UIOManager.I().showMessageDialog("Please add your pinterest.com account at Settings->Account manager to find more than " + max_entries_per_page_free + " images");
            }
        }
    }

    private String getJsonSourceFromHTML(final Browser br) {
        String json_source_from_html;
        if (this.loggedIN) {
            json_source_from_html = br.getRegex("id=\\'initial\\-state\\'>window\\.__INITIAL_STATE__ =(.*?)</script>").getMatch(0);
        } else {
            json_source_from_html = br.getRegex("id=\\'initial\\-state\\'>(\\{.*?\\})</script>").getMatch(0);
            if (json_source_from_html == null) {
                json_source_from_html = br.getRegex("P\\.main\\.start\\((\\{.*?\\})\\);[\t\n\r]+").getMatch(0);
            }
            if (json_source_from_html == null) {
                json_source_from_html = br.getRegex("P\\.startArgs\\s*=\\s*(\\{.*?\\});[\t\n\r]+").getMatch(0);
            }
            if (json_source_from_html == null) {
                json_source_from_html = br.getRegex("id=\\'jsInit1\\'>(\\{.*?\\})</script>").getMatch(0);
            }
        }
        return json_source_from_html;
    }

    private boolean proccessLinkedHashMap(LinkedHashMap<String, Object> single_pinterest_data, final String board_id, final String source_url) throws PluginException {
        final String type = getStringFromJson(single_pinterest_data, "type");
        if (type == null || !(type.equals("pin") || type.equals("interest"))) {
            /* Skip invalid objects! */
            return false;
        }
        final LinkedHashMap<String, Object> single_pinterest_pinner = (LinkedHashMap<String, Object>) single_pinterest_data.get("pinner");
        final LinkedHashMap<String, Object> single_pinterest_images = (LinkedHashMap<String, Object>) single_pinterest_data.get("images");
        LinkedHashMap<String, Object> single_pinterest_images_original = null;
        if (single_pinterest_images != null) {
            single_pinterest_images_original = (LinkedHashMap<String, Object>) single_pinterest_images.get("orig");
        }
        final Object usernameo = single_pinterest_pinner != null ? single_pinterest_pinner.get("username") : null;
        // final Object pinner_nameo = single_pinterest_pinner != null ? single_pinterest_pinner.get("full_name") : null;
        LinkedHashMap<String, Object> tempmap = null;
        String directlink = null;
        if (single_pinterest_images_original != null) {
            /* Original image available --> Take that */
            directlink = (String) single_pinterest_images_original.get("url");
        } else {
            if (single_pinterest_images != null) {
                /* Original image NOT available --> Take the best we can find */
                final Iterator<Entry<String, Object>> it = single_pinterest_images.entrySet().iterator();
                while (it.hasNext()) {
                    final Entry<String, Object> ipentry = it.next();
                    tempmap = (LinkedHashMap<String, Object>) ipentry.getValue();
                    /* First image = highest (but original is somewhere 'in the middle') */
                    break;
                }
                directlink = (String) tempmap.get("url");
            } else {
                /* 2017-11-22: Special case, for "preview PINs" */
                final String[] image_sizes = new String[] { "image_xlarge_url", "image_large_url", "image_medium_url" };
                for (final String image_size : image_sizes) {
                    directlink = (String) single_pinterest_data.get(image_size);
                    if (directlink != null) {
                        break;
                    }
                }
            }
        }
        final String pin_id = (String) single_pinterest_data.get("id");
        final String username = usernameo != null ? (String) usernameo : null;
        // final String pinner_name = pinner_nameo != null ? (String) pinner_nameo : null;
        if (StringUtils.isEmpty(pin_id) || directlink == null) {
            logger.warning("Decrypter broken for link: " + parameter);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        } else if (dupeList.contains(pin_id)) {
            logger.info("Skipping duplicate: " + pin_id);
            return true;
        }
        dupeList.add(pin_id);
        final String content_url = "https://www." + this.getHost() + "/pin/" + pin_id + "/";
        final DownloadLink dl = createPINDownloadUrl(content_url);
        if (!StringUtils.isEmpty(board_id)) {
            dl.setProperty("boardid", board_id);
        }
        if (!StringUtils.isEmpty(source_url)) {
            dl.setProperty("source_url", source_url);
        }
        if (!StringUtils.isEmpty(username)) {
            dl.setProperty("username", username);
        }
        setInfoOnDownloadLink(this.br, dl, single_pinterest_data, directlink, loggedIN);
        fp.add(dl);
        decryptedLinks.add(dl);
        distribute(dl);
        numberof_pins_decrypted_via_current_function++;
        final String externalURL = getAlternativeExternalURLInPINMap(single_pinterest_data);
        if (externalURL != null && this.enable_crawl_alternative_URL) {
            this.decryptedLinks.add(this.createDownloadlink(externalURL));
        }
        return true;
    }

    /* Wrapper which either returns object as String or (e.g. it is missing or different datatype), null. */
    private String getStringFromJson(final LinkedHashMap<String, Object> entries, final String key) {
        final String output;
        final Object jsono = entries.get(key);
        if (jsono != null && jsono instanceof String) {
            output = (String) jsono;
        } else {
            output = null;
        }
        return output;
    }

    private String getBoardID(final String json_source) {
        /* This board_id RegEx will usually only work when loggedOFF */
        String board_id = PluginJSonUtils.getJsonValue(json_source, "board_id");
        if (board_id == null) {
            /* For LoggedIN and loggedOFF */
            board_id = this.br.getRegex("(\\d+)_board_thumbnail").getMatch(0);
        }
        return board_id;
    }

    /**
     * Recursive function to crawl all PINs --> Easiest way as they often change their json.
     *
     */
    @SuppressWarnings("unchecked")
    private void processPinsKamikaze(final Object jsono, final String board_id, final String source_url) throws PluginException {
        LinkedHashMap<String, Object> test;
        if (jsono instanceof LinkedHashMap) {
            test = (LinkedHashMap<String, Object>) jsono;
            if (!proccessLinkedHashMap(test, board_id, source_url)) {
                final Iterator<Entry<String, Object>> it = test.entrySet().iterator();
                while (it.hasNext()) {
                    final Entry<String, Object> thisentry = it.next();
                    final Object mapObject = thisentry.getValue();
                    processPinsKamikaze(mapObject, board_id, source_url);
                }
            }
        } else if (jsono instanceof ArrayList) {
            ArrayList<Object> ressourcelist = (ArrayList<Object>) jsono;
            for (final Object listo : ressourcelist) {
                processPinsKamikaze(listo, board_id, source_url);
            }
        }
    }

    private void decryptSite() {
        /*
         * Also possible using json of P.start.start( to get the first 25 entries: resourceDataCache --> Last[] --> data --> Here we go --->
         * But I consider this as an unsafe method.
         */
        final String[] linkinfo = br.getRegex("<div class=\"bulkEditPinWrapper\">(.*?)class=\"creditTitle\"").getColumn(0);
        if (linkinfo == null || linkinfo.length == 0) {
            logger.warning("Decrypter broken for link: " + parameter);
            decryptedLinks = null;
            return;
        }
        for (final String sinfo : linkinfo) {
            String description = new Regex(sinfo, "title=\"([^<>\"]*?)\"").getMatch(0);
            if (description == null) {
                description = new Regex(sinfo, "<p class=\"pinDescription\">([^<>]*?)<").getMatch(0);
            }
            final String directlink = new Regex(sinfo, "\"(https?://[a-z0-9\\.\\-]+/originals/[^<>\"]*?)\"").getMatch(0);
            final String pin_id = new Regex(sinfo, "/pin/([A-Za-z0-9\\-_]+)/").getMatch(0);
            if (pin_id == null) {
                logger.warning("Decrypter broken for link: " + parameter);
                decryptedLinks = null;
                return;
            } else if (dupeList.contains(pin_id)) {
                logger.info("Skipping duplicate: " + pin_id);
                continue;
            }
            dupeList.add(pin_id);
            numberof_pins_decrypted_via_current_function++;
            String filename = pin_id;
            final String content_url = "http://www.pinterest.com/pin/" + pin_id + "/";
            final DownloadLink dl = createDownloadlink(content_url);
            dl.setContentUrl(content_url);
            dl.setLinkID(jd.plugins.hoster.PinterestCom.getLinkidForInternalDuplicateCheck(content_url, directlink));
            dl._setFilePackage(fp);
            if (directlink != null) {
                dl.setProperty("free_directlink", directlink);
            }
            if (description != null) {
                dl.setComment(description);
                dl.setProperty("description", description);
                if (enable_description_inside_filenames) {
                    filename += "_" + description;
                }
            }
            filename = encodeUnicode(filename);
            dl.setProperty("decryptedfilename", filename);
            dl.setName(filename + ".jpg");
            dl.setAvailable(true);
            dl.setMimeHint(CompiledFiletypeFilter.ImageExtensions.JPG);
            decryptedLinks.add(dl);
            distribute(dl);
        }
    }

    private DownloadLink createPINDownloadUrl(String url) {
        url = url.replaceAll("https?://", "decryptedpinterest://");
        return this.createDownloadlink(url);
    }

    private DownloadLink getOffline(final String parameter) {
        final DownloadLink offline = createDownloadlink("directhttp://" + parameter);
        offline.setFinalFileName(new Regex(parameter, "https?://[^<>\"/]+/(.+)").getMatch(0));
        offline.setAvailable(false);
        offline.setProperty("offline", true);
        return offline;
    }

    /** Log in the account of the hostplugin */
    @SuppressWarnings({ "deprecation" })
    private boolean getUserLogin(final boolean force) throws Exception {
        final PluginForHost hostPlugin = JDUtilities.getPluginForHost(this.getHost());
        final Account aa = AccountController.getInstance().getValidAccount(hostPlugin);
        if (aa == null) {
            logger.warning("There is no account available, stopping...");
            return false;
        }
        try {
            jd.plugins.hoster.PinterestCom.login(this.br, aa, force);
        } catch (final PluginException e) {
            aa.setValid(false);
            return false;
        }
        return true;
    }

    /** Wrapper */
    public static void prepAPIBR(final Browser br) throws PluginException {
        jd.plugins.hoster.PinterestCom.prepAPIBR(br);
    }

    private void prepAPIBRCrawler(final Browser br) throws PluginException {
        jd.plugins.hoster.PinterestCom.prepAPIBR(br);
        br.setAllowedResponseCodes(new int[] { 503, 504 });
    }
}
