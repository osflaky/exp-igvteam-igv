package org.igv.util.stream;

import htsjdk.samtools.seekablestream.SeekableStream;
import org.igv.exceptions.HttpResponseException;
import org.igv.logging.*;
import org.igv.util.HttpUtils;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 *
 */
public class IGVSeekableHTTPStream extends SeekableStream {

    static Logger log = LogManager.getLogger(IGVSeekableHTTPStream.class);

    private long position = 0;
    private URL url;
    long contentLength = -1;                      // Not set

    public IGVSeekableHTTPStream(final URL url) {
        this.url = url;
    }

    public long position() {
        return position;
    }

    public long length() {
        return contentLength;
    }

    @Override
    public long skip(long n) throws IOException {
        long bytesToSkip = contentLength < 0 ? n : Math.min(n, contentLength - position);
        position += bytesToSkip;
        return bytesToSkip;
    }

    public boolean eof() throws IOException {
        return contentLength > 0 && position >= contentLength;
    }

    public void seek(final long position) {
        this.position = position;
    }

    public int read(byte[] buffer, int offset, int len) throws IOException {

        int attempts = 0;
        while (attempts < 3) {
            try {
                return _read(buffer, offset, len);
            } catch (java.net.SocketException e) {
                if (attempts < 3) {
                    attempts++;
                    log.error("Socket exception. Trying again.", e);
                } else {
                    throw e;
                }
            }
        }

        throw new RuntimeException("Reading " + url + " failed with unknown error.");  // Should be impossible to get here
    }

    public int _read(byte[] buffer, int offset, int len) throws IOException {

        if (offset < 0 || len < 0 || (offset + len) > buffer.length) {
            String stats = "Offset=" + offset + ",len=" + len + ",buflen=" + buffer.length;
            throw new IndexOutOfBoundsException(stats);
        }
        if (len == 0) {
            return 0;
        }

        InputStream is = null;
        int n = 0;
        try {

            if (contentLength > 0 && position >= contentLength) {
                return -1;  // EOF
            }

            long endRange = position + len - 1;
            // IF we know the total content length, limit the end range to that.
            if (contentLength > 0) {
                endRange = Math.min(endRange, contentLength - 1);   // Range end is inclusive
            }
            if (log.isTraceEnabled()) {
                log.trace("Trying to read range " + position + " to " + endRange);
            }
            is = openInputStreamForRange(position, endRange);

            while (n < len) {
                int count = is.read(buffer, offset + n, len - n);
                if (count < 0) {
                    if (n == 0) {
                        return -1;
                    } else {
                        break;
                    }
                }
                n += count;
            }

            position += n;
            return n;

        } catch (HttpUtils.UnsatisfiableRangeException e) {
            return handleUnsatisfiableRange(n);
        } catch (IOException e) {

            if (e.getMessage().contains("416") || (e instanceof EOFException)) {
                return handleUnsatisfiableRange(n);
            } else {
                throw e;
            }

        } finally {
            if (is != null) {
                is.close();
            }
        }
    }

    private int handleUnsatisfiableRange(int n) {
        if (n == 0) {
            contentLength = position;
            return -1;
        } else {
            position += n;
            // As we are at EOF, the contentLength and position are by definition =
            contentLength = position;
            return n;
        }
    }


    public void close() throws IOException {
        // Nothing to do
    }


    public int read() throws IOException {
        byte[] tmp = new byte[1];
        read(tmp, 0, 1);
        return (int) tmp[0] & 0xFF;
    }

    public InputStream openInputStreamForRange(long start, long end) throws IOException {

        // To safely do a range query we should limit it to the content length.  Try to determine content-length,
        // there is no guarantee this will succeed.
//        if (!HttpUtils.isSignedURL(url.toExternalForm())) {
//            try {
//                contentLength = HttpUtils.getInstance().getContentLength(url);
//            } catch (Exception e) {
//                log.error("Error fetching content-length", e);
//            }
//        }
//
//        // Check content length, if known
//        if (contentLength > 0) {
//            if (start >= contentLength) {
//                throw new IOException("Start (" + start + ") is > content length(" + contentLength + ")");
//            } else if (start == contentLength) {
//
//            } else {
//                end = contentLength - 1;
//            }
//        }

        String byteRange = "bytes=" + start + "-" + end;
        Map<String, String> params = new HashMap();
        params.put("Range", byteRange);
        //URL url = addStartEndQueryString(this.url, start, end);

        HttpURLConnection conn = HttpUtils.getInstance().openConnection(url, params);

        try {
            InputStream input = conn.getInputStream();
            if (contentLength < 0) {
                setContentLength(conn);
            }
            return input;
        } catch (IOException e) {
            HttpUtils.getInstance().readErrorStream(conn);  // Consume content
            throw e;
        }
    }


    /**
     * Record the total content length from the "Content-Range" header of a partial response.  This is free -- the
     * value comes back with data we are fetching anyway -- and so avoids a separate HEAD request.  Knowing the length
     * lets buffered reads clamp to the end of the file rather than discovering EOF with a wasted 416.
     * <p>
     * A server that does not honor the range request returns 200 with no "Content-Range", in which case the length
     * is left unset.
     *
     * @param conn a connection whose response headers have been read
     */
    private void setContentLength(HttpURLConnection conn) {
        // "Content-Range: bytes <start>-<end>/<total>", where total is "*" if the server does not know it
        String contentRange = conn.getHeaderField("Content-Range");
        if (contentRange != null) {
            int idx = contentRange.lastIndexOf('/');
            if (idx > 0) {
                try {
                    contentLength = Long.parseLong(contentRange.substring(idx + 1).trim());
                } catch (NumberFormatException e) {
                    log.debug("Unparseable Content-Range: " + contentRange);
                }
            }
        }
    }

    /**
     * Add query parameters which should more properly be in Range header field
     * to query string
     *
     * @param start start byte
     * @param end   end byte
     * @throws java.net.MalformedURLException
     */
    static URL addStartEndQueryString(URL url, long start, long end) throws MalformedURLException {

        String queryString = url.getQuery();
        if (queryString == null) {
            return HttpUtils.createURL(url.toExternalForm() + "?start=" + start + "&end=" + end);
        } else {
            String newQueryString = queryString + "&start=" + start + "&end" + end;
            return HttpUtils.createURL(url.toExternalForm().replace(queryString, newQueryString));
        }
    }

    @Override
    public String getSource() {
        return url.toExternalForm();
    }


    public static void main(String[] args) throws IOException {

        IGVSeekableHTTPStream stream = new IGVSeekableHTTPStream(HttpUtils.createURL("http://localhost/igv-web/test/data/misc/BufferedReaderTest.bin"));

        byte[] buffer = new byte[1000];

        stream.read(buffer, 0, 1000);

        System.out.println("Done");

    }
}
