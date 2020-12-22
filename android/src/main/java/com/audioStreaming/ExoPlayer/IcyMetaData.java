package com.audioStreaming.ExoPlayer;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IcyMetaData {
    public static Map<String, String> cachedMeta = new HashMap<>();
    private boolean isError;

    public IcyMetaData() {
        isError = false;
    }

    public Map<String, String> loadMeta(String url) throws IOException {
        InputStreamReader stream = null;
        URLConnection con = new URL(url).openConnection();
        try {
            con.setRequestProperty("Icy-MetaData", "1");
            con.setRequestProperty("Connection", "close");
            con.setRequestProperty("Accept", null);
            con.connect();
            int metaDataOffset = 0;
            Map<String, List<String>> headers = con.getHeaderFields();
            stream = new InputStreamReader(con.getInputStream(), "windows-1251");

            if (headers.containsKey("icy-metaint")) {
                // Headers are sent via HTTP
                metaDataOffset = Integer.parseInt(headers.get("icy-metaint").get(0));
            } else {
                // Headers are sent within a stream
                StringBuilder strHeaders = new StringBuilder();
                char c;
                while ((c = (char) stream.read()) != -1) {
                    strHeaders.append(c);
                    if (strHeaders.length() > 5 && (strHeaders.substring((strHeaders.length() - 4), strHeaders.length()).equals("\r\n\r\n"))) {
                        // end of headers
                        break;
                    }
                }

                // Match headers to get metadata offset within a stream
                Pattern p = Pattern.compile("\\r\\n(icy-metaint):\\s*(.*)\\r\\n");
                Matcher m = p.matcher(strHeaders.toString());
                if (m.find()) {
                    metaDataOffset = Integer.parseInt(m.group(2));
                }
            }

            // In case no data was sent
            if (metaDataOffset == 0) {
                isError = true;
                return null;
            }

            // Read metadata
            int b;
            int count = 0;
            int metaDataLength = 4080; // 4080 is the max length
            boolean inData = false;
            StringBuilder metaData = new StringBuilder();
            // Stream position should be either at the beginning or right after headers
            while ((b = stream.read()) != -1) {
                count++;

                // Length of the metadata
                if (count == metaDataOffset + 1) {
                    metaDataLength = b * 16;
                }

                if (count > metaDataOffset + 1 && count < (metaDataOffset + metaDataLength)) {
                    inData = true;
                } else {
                    inData = false;
                }
                if (inData) {
                    if (b != 0) {
                        metaData.append((char) b);
                    }
                }
                if (count > (metaDataOffset + metaDataLength)) {
                    break;
                }
            }


            // Set the data
            return IcyMetaData.parseMetadata(metaData.toString());
        } finally {
            // Close
            if(stream != null) {
                stream.close();
            }
            if(con != null) {
                HttpURLConnection c = (HttpURLConnection) con;
                c.disconnect();
            }
        }
    }

    public boolean isError() {
        return isError;
    }

    public static Map<String, String> parseMetadata(String metaString) {
        HashMap<String, String> metaData = new HashMap<>();
        String[] kvs = metaString.split( ";" );

        for (String kv : kvs) {
            int n = kv.indexOf( '=' );
            if (n < 1) continue;

            boolean isString = n + 1 < kv.length()
                    && kv.charAt( kv.length() - 1) == '\''
                    && kv.charAt( n + 1 ) == '\'';

            String key = kv.substring( 0, n );
            String val = isString ?
                    kv.substring( n+2, kv.length()-1) :
                    n + 1 < kv.length() ?
                            kv.substring( n+1 ) : "";
            try {
                metaData.put(key, new String(val.getBytes("windows-1251"), "utf-8"));
            } catch (UnsupportedEncodingException e) {
                metaData.put(key, val);
            }
        }

        return metaData;
    }
}