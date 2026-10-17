package org.igv.util;

import org.igv.logging.LogManager;
import org.igv.logging.Logger;
import org.igv.prefs.PreferencesManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HttpMappings {

    private static Logger log = LogManager.getLogger(HttpMappings.class);
    private static Map<String, String> mappedURLCache = new ConcurrentHashMap<>();

    private static final String MAPPING_URL =
            "https://raw.githubusercontent.com/igvteam/igv-data/refs/heads/main/data/url_mappings.tsv";

    static {
        mappedURLCache.put(MAPPING_URL, MAPPING_URL);
    }

    static Map<String, String> urlMappings = new ConcurrentHashMap<>();

    /**
     * Bound the wait on the mapping table.  Unlike igv.js, which consults the table only after a request has already
     * failed, this fetch is on the main path -- the first url IGV maps blocks on it -- so an unreachable or slow host
     * must not stall genome loading.
     */
    private static final int MAPPING_TIMEOUT = 5000;

    /**
     * Set once the table has been fetched, successfully or not.  A failure is not retried: the fetch is on the main
     * path, and retrying it on every subsequent url would pay the timeout over and over.
     */
    private static volatile boolean mappingsLoaded = false;

    /**
     * Hosts that have moved wholesale.  Unlike the entries in the mapping table these are keyed by prefix, so one
     * rule retires every resource under the old host.  Order matters: the first matching prefix wins, so a rule for
     * a path under a retired host must precede the rule for the host itself.  The replacements are https only.
     *
     * Kept in sync with the RETIRED_HOSTS table in igv.js (igv-utils "igvxhr").
     */
    private static final List<String[]> RETIRED_HOSTS = List.of(
            new String[]{"//data.broadinstitute.org/igvdata/tcga", "//igv.org/tcga"},
            new String[]{"//www.broadinstitute.org/igvdata/tcga", "//igv.org/tcga"},
            new String[]{"//www.broadinstitute.org/igvdata", "//data.broadinstitute.org/igvdata"},
            new String[]{"//igvdata.broadinstitute.org", "//s3.amazonaws.com/igv.broadinstitute.org"},
            new String[]{"//igv.broadinstitute.org", "//s3.amazonaws.com/igv.broadinstitute.org"},
            new String[]{"//dn7ywbm9isq8j.cloudfront.net", "//s3.amazonaws.com/igv.broadinstitute.org"},
            new String[]{"//igv.genepattern.org", "//igv-genepattern-org.s3.us-east-1.amazonaws.com"}
    );


    private HttpMappings() {
        // Prevent instantiation
    }

    /**
     * Map a URL, for example from a deprecated host or non-http scheme, to a stable newer form. Sort of a pre-request
     * redirect.  This should be used to map to a stable, long-term URL, not to for example time-limited signed URLs.
     *
     * @param urlString
     * @return
     * @throws MalformedURLException
     */
    public static String mapURL(String urlString) throws MalformedURLException {

        // Check cache to avoid unnecessary lookups.  Neither map holds null values, so a null get means "absent".
        String cached = mappedURLCache.get(urlString);
        if (cached != null) {
            return cached;
        }

        // checkStaticMappings below reassigns urlString -- the cache must be keyed on the url the caller asked for
        final String requestedURL = urlString;

        if (!mappingsLoaded) {
            loadMappings();
        }

        String mappedURL = urlMappings.get(urlString);
        if (mappedURL == null) {
            urlString = checkStaticMappings(urlString);
            String key = urlString.startsWith("s3://") || urlString.contains("amazonaws.com") ? getAmazonKey(urlString) : urlString;
            mappedURL = urlMappings.getOrDefault(key, urlString);
        }
        mappedURLCache.put(requestedURL, mappedURL);       // Record even if not mapped to prevent further lookups
        return mappedURL;
    }

    private static synchronized void loadMappings() {

        if (mappingsLoaded) {
            return;     // Another thread got here first
        }
        mappingsLoaded = true;      // Set before the fetch -- a failure is recorded, not retried

        try {
            URL fileUrl = new URL(MAPPING_URL);
            URLConnection conn = fileUrl.openConnection();
            conn.setConnectTimeout(MAPPING_TIMEOUT);
            conn.setReadTimeout(MAPPING_TIMEOUT);
            try (InputStream inputStream = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#") || line.trim().isEmpty()) continue; // Skip comment lines
                    String[] columns = line.split("\t");
                    if (columns.length == 2) {
                        String key = columns[0].trim();
                        // For Amazon S3 URLs use the item's path.  Important because S3 urls can be in global or regional form.
                        if (key.startsWith("s3://") || key.contains("amazonaws.com")) {
                            key = getAmazonKey(key);
                        }
                        urlMappings.put(key, columns[1].trim());
                    }
                }
            }
        } catch (IOException e) {
            log.error("Error loading URL mappings", e);
        }
    }

    /*
     * Convert an Amazon S3 URL to a key to account for global vs regional forms.
     */
    private static String getAmazonKey(String url) throws MalformedURLException {
        return "S3::: " + (new URL(url.replace("s3://", "https://")).getPath());
    }

    /**
     * Return the replacement for a url under a retired host, or null if it is not under one.  These are not a
     * fallback: the old host cannot serve the resource, so the substitution is made before the request goes out.
     */
    private static String mapRetiredHost(String urlString) {
        for (String[] rule : RETIRED_HOSTS) {
            if (urlString.contains(rule[0])) {
                // These urls are widely bookmarked in their original http form
                return urlString.replace(rule[0], rule[1]).replaceFirst("^http:", "https:");
            }
        }
        return null;
    }

    private static String checkStaticMappings(String urlString) throws MalformedURLException {
        if (urlString.startsWith("htsget://")) {
            urlString = urlString.replace("htsget://", "https://");
        } else if (urlString.startsWith("gs://")) {
            urlString = GoogleUtils.translateGoogleCloudURL(urlString);
        } else if (urlString.startsWith("ftp://ftp.ncbi.nlm.nih.gov/geo")) {
            urlString = urlString.replace("ftp://", "https://");
        }

        if (GoogleUtils.isGoogleURL(urlString)) {
            if (urlString.indexOf("alt=media") < 0) {
                urlString = URLUtils.addParameter(urlString, "alt=media");
            }
        }

        String retired = mapRetiredHost(urlString);
        if (retired != null) {
            return retired;
        }

        String host = URLUtils.getHost(urlString);
        if (host.equals("www.dropbox.com")) {
            urlString = urlString.replace("//www.dropbox.com", "//dl.dropboxusercontent.com");
        } else if (host.equals("drive.google.com")) {
            urlString = GoogleUtils.driveDownloadURL(urlString);
        }

        // data.broadinstitute.org requires https
        urlString = urlString.replace("http://data.broadinstitute.org", "https://data.broadinstitute.org");

        return urlString;
    }

}
