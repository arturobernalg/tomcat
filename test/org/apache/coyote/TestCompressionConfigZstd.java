/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;

import org.apache.coyote.http11.filters.ZstdEncoder;

/*
 * Zstd is only used if the Zstd library is available so these tests are skipped if it is not.
 */
@RunWith(Parameterized.class)
public class TestCompressionConfigZstd {

    @Parameterized.Parameters(name = "{index}: accept-encoding[{2}], expected[{1}]")
    public static Collection<Object[]> parameters() {
        List<Object[]> parameterSets = new ArrayList<>();

        // The encoding that is expected is null if the response is not compressed
        parameterSets.add(new Object[] { new String[] { "zstd" }, "zstd" });
        parameterSets.add(new Object[] { new String[] { "ZSTD" }, "zstd" });
        parameterSets.add(new Object[] { new String[] { "gzip" }, "gzip" });
        parameterSets.add(new Object[] { new String[] { "zstd, gzip" }, "zstd" });
        parameterSets.add(new Object[] { new String[] { "gzip, zstd" }, "zstd" });
        parameterSets.add(new Object[] { new String[] { "gzip", "zstd" }, "zstd" });
        parameterSets.add(new Object[] { new String[] { "gzip, deflate, zstd" }, "zstd" });
        // The user-agent's preference is respected
        parameterSets.add(new Object[] { new String[] { "zstd;q=0.5, gzip" }, "gzip" });
        parameterSets.add(new Object[] { new String[] { "zstd, gzip;q=0.5" }, "zstd" });
        parameterSets.add(new Object[] { new String[] { "zstd;q=0.8, gzip;q=0.8" }, "zstd" });
        // A weight of zero means not acceptable
        parameterSets.add(new Object[] { new String[] { "zstd;q=0, gzip" }, "gzip" });
        parameterSets.add(new Object[] { new String[] { "zstd, gzip;q=0" }, "zstd" });
        parameterSets.add(new Object[] { new String[] { "zstd;q=0" }, null });
        parameterSets.add(new Object[] { new String[] { "zstd;q=0, gzip;q=0" }, null });
        parameterSets.add(new Object[] { new String[] { "zstd", "zstd;q=0" }, null });
        // "*" matches the encodings that are not listed. gzip is used if the user-agent has no preference.
        parameterSets.add(new Object[] { new String[] { "*" }, "gzip" });
        parameterSets.add(new Object[] { new String[] { "*, zstd;q=0" }, "gzip" });
        parameterSets.add(new Object[] { new String[] { "*, gzip;q=0" }, "zstd" });
        parameterSets.add(new Object[] { new String[] { "*;q=0" }, null });
        parameterSets.add(new Object[] { new String[] { "*;q=0, zstd" }, "zstd" });
        parameterSets.add(new Object[] { new String[] { "zstd;q=0.1, *" }, "gzip" });
        // Nothing that can be used
        parameterSets.add(new Object[] { new String[] {}, null });
        parameterSets.add(new Object[] { new String[] { "identity" }, null });
        parameterSets.add(new Object[] { new String[] { "deflate, br" }, null });
        parameterSets.add(new Object[] { new String[] { "xzstd" }, null });
        parameterSets.add(new Object[] { new String[] { "<>zstd" }, null });
        parameterSets.add(new Object[] { new String[] { "zstd;q=abc" }, null });

        List<Object[]> result = new ArrayList<>(parameterSets.size());
        for (Object[] parameterSet : parameterSets) {
            StringJoiner description = new StringJoiner(", ");
            for (String header : (String[]) parameterSet[0]) {
                description.add('"' + header + '"');
            }
            result.add(new Object[] { parameterSet[0], parameterSet[1], description.toString() });
        }
        return result;
    }

    @Parameter(0)
    public String[] headers;
    @Parameter(1)
    public String expected;
    @Parameter(2)
    public String headersDescription;


    @Before
    public void checkZstdIsAvailable() {
        Assume.assumeTrue(ZstdEncoder.isAvailable());
    }


    private Response compress(CompressionConfig compressionConfig, String... acceptEncodings) {
        Request request = new Request();
        for (String acceptEncoding : acceptEncodings) {
            request.getMimeHeaders().addValue("accept-encoding").setString(acceptEncoding);
        }
        Response response = new Response();
        response.setContentType("text/plain");
        compressionConfig.useCompression(request, response);
        return response;
    }


    @Test
    public void testNegotiation() {
        CompressionConfig compressionConfig = new CompressionConfig();
        compressionConfig.setCompression("on");
        compressionConfig.setCompressionZstdLevel(3);

        Request request = new Request();
        for (String header : headers) {
            request.getMimeHeaders().addValue("accept-encoding").setString(header);
        }
        Response response = new Response();
        response.setContentType("text/plain");
        response.setContentLength(1_000_000);

        boolean useCompression = compressionConfig.useCompression(request, response);

        Assert.assertEquals(expected != null, useCompression);
        Assert.assertEquals(expected, response.getMimeHeaders().getHeader("Content-Encoding"));
        Assert.assertEquals("zstd".equals(expected), CompressionConfig.isZstd(response));
        if (useCompression) {
            Assert.assertEquals(-1, response.getContentLengthLong());
        }
        Assert.assertEquals("accept-encoding", response.getMimeHeaders().getHeader("Vary"));
    }


    @Test
    public void testZstdDisabledByDefault() {
        CompressionConfig compressionConfig = new CompressionConfig();
        compressionConfig.setCompression("on");
        Assert.assertEquals(0, compressionConfig.getCompressionZstdLevel());

        Response response = compress(compressionConfig, headers);

        // Only gzip, which is what was used before Zstd was supported
        Assert.assertFalse(CompressionConfig.isZstd(response));
        String contentEncoding = response.getMimeHeaders().getHeader("Content-Encoding");
        Assert.assertTrue(contentEncoding, contentEncoding == null || "gzip".equals(contentEncoding));
    }


    @Test
    public void testTransferEncodingIsNotAffected() {
        CompressionConfig compressionConfig = new CompressionConfig();
        compressionConfig.setCompression("force");
        compressionConfig.setCompressionZstdLevel(3);
        Request request = new Request();
        request.getMimeHeaders().addValue("TE").setString("gzip");
        request.getMimeHeaders().addValue("accept-encoding").setString("zstd");
        Response response = new Response();

        Assert.assertTrue(compressionConfig.useCompression(request, response));
        Assert.assertEquals("gzip", response.getMimeHeaders().getHeader("Transfer-Encoding"));
        Assert.assertNull(response.getMimeHeaders().getHeader("Content-Encoding"));
        Assert.assertFalse(CompressionConfig.isZstd(response));
    }


    @Test
    public void testExistingChecksApplyToZstd() {
        CompressionConfig compressionConfig = new CompressionConfig();
        compressionConfig.setCompression("on");
        compressionConfig.setCompressionZstdLevel(3);

        // Strong ETag
        Response response = new Response();
        response.setContentType("text/plain");
        response.getMimeHeaders().addValue("ETag").setString("\"abc\"");
        Request request = new Request();
        request.getMimeHeaders().addValue("accept-encoding").setString("zstd");
        Assert.assertFalse(compressionConfig.useCompression(request, response));

        // Weak ETag
        response = new Response();
        response.setContentType("text/plain");
        response.getMimeHeaders().addValue("ETag").setString("W/\"abc\"");
        Assert.assertTrue(compressionConfig.useCompression(request, response));

        // Already encoded
        response = new Response();
        response.setContentType("text/plain");
        response.getMimeHeaders().addValue("Content-Encoding").setString("zstd");
        Assert.assertFalse(compressionConfig.useCompression(request, response));

        // Partial content
        response = new Response();
        response.setContentType("text/plain");
        response.setStatus(206);
        Assert.assertFalse(compressionConfig.useCompression(request, response));

        // Too small and the wrong type
        response = new Response();
        response.setContentType("text/plain");
        response.setContentLength(10);
        Assert.assertFalse(compressionConfig.useCompression(request, response));
        response = new Response();
        response.setContentType("image/png");
        Assert.assertFalse(compressionConfig.useCompression(request, response));
    }


    /*
     * The level is read from the configuration once, when Zstd is chosen. It is held by the response and is that
     * which was used to choose Zstd, whatever happens to the configuration after that.
     */
    @Test
    public void testLevelIsHeldByTheResponse() {
        CompressionConfig compressionConfig = new CompressionConfig();
        compressionConfig.setCompression("force");
        compressionConfig.setCompressionZstdLevel(7);

        Request request = new Request();
        request.getMimeHeaders().addValue("accept-encoding").setString("zstd");
        Response response = new Response();
        Assert.assertTrue(compressionConfig.useCompression(request, response));
        Assert.assertEquals(7, CompressionConfig.getZstdLevel(response));

        for (int level : new int[] { 0, 1, 22 }) {
            compressionConfig.setCompressionZstdLevel(level);
            Assert.assertEquals(7, CompressionConfig.getZstdLevel(response));
        }

        // A response that is reused, as HTTP/1.1 does, must not see the level of the previous one. It is not
        // compressed with Zstd.
        response.recycle();
        compressionConfig.setCompressionZstdLevel(0);
        Request gzipRequest = new Request();
        gzipRequest.getMimeHeaders().addValue("accept-encoding").setString("gzip");
        Assert.assertTrue(compressionConfig.useCompression(gzipRequest, response));
        Assert.assertEquals(0, CompressionConfig.getZstdLevel(response));
        response.recycle();
        Assert.assertEquals(0, CompressionConfig.getZstdLevel(response));
        Assert.assertEquals(0, CompressionConfig.getZstdLevel(new Response()));
    }


    @Test
    public void testLevel() {
        CompressionConfig compressionConfig = new CompressionConfig();
        Assert.assertEquals(0, compressionConfig.getCompressionZstdLevel());
        for (int level : new int[] { 0, 1, 5, 22 }) {
            compressionConfig.setCompressionZstdLevel(level);
            Assert.assertEquals(level, compressionConfig.getCompressionZstdLevel());
        }
        for (int level : new int[] { -1, 23, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
            try {
                compressionConfig.setCompressionZstdLevel(level);
                Assert.fail("Expected IllegalArgumentException for " + level);
            } catch (IllegalArgumentException expectedException) {
                // Expected
            }
            // The previous value is kept
            Assert.assertEquals(22, compressionConfig.getCompressionZstdLevel());
        }
    }
}
