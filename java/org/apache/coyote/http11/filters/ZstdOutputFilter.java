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
package org.apache.coyote.http11.filters;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.coyote.CompressionConfig;
import org.apache.coyote.Response;
import org.apache.coyote.http11.HttpOutputBuffer;
import org.apache.coyote.http11.OutputFilter;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.res.StringManager;

/**
 * Output filter that compresses response data using the Zstd algorithm.
 * <p>
 * The encoder uses memory that is not managed by the JVM so it is released as soon as it is no longer needed. That is
 * when the response ends, when compression fails, when the filter is recycled or when it is closed. A response can be
 * abandoned without ending, e.g. if the client resets an HTTP/2 stream, and the thread that cleans up is not
 * necessarily the thread that is writing the response, so {@link #close()} can be called at any time.
 * <p>
 * Once compression has failed, the Zstd stream of the response is not complete and can not be continued. The filter
 * does not start another one. All further writes fail until the filter is recycled.
 * <p>
 * The level is the one that was used to choose Zstd for the response, which is held by the response. It is not
 * read from the configuration again, which can be changed at any time, e.g. with JMX, so a response is always
 * compressed with a valid level that is consistent with the choice to use Zstd.
 */
public class ZstdOutputFilter implements OutputFilter, Closeable {

    private static final Log log = LogFactory.getLog(ZstdOutputFilter.class);
    private static final StringManager sm = StringManager.getManager(ZstdOutputFilter.class);

    // The compressed data is written to the next buffer in pieces of this size
    private static final int OUTPUT_SIZE = 8 * 1024;

    // The level for the current response
    private int level;

    private HttpOutputBuffer buffer;
    // May be released by a thread other than the one that is writing the response
    private volatile ZstdEncoder encoder;
    // Only used by the thread that is writing the response
    private ByteBuffer output;
    // The Zstd stream has been completed
    private volatile boolean completed;
    // The Zstd stream can not be completed because compression failed or the filter was closed
    private volatile boolean failed;


    /**
     * Constructs a new ZstdOutputFilter. {@link #setResponse(Response)} must be called before the filter is used for
     * a response.
     */
    public ZstdOutputFilter() {
    }


    @Override
    public int doWrite(ByteBuffer chunk) throws IOException {
        int len = chunk.remaining();
        compress(chunk, ZstdEncoder.PROCESS);
        return len;
    }


    @Override
    public long getBytesWritten() {
        return buffer.getBytesWritten();
    }


    @Override
    public void flush() throws IOException {
        if (encoder != null) {
            try {
                compress(ByteBuffer.allocate(0), ZstdEncoder.FLUSH);
            } catch (IOException ioe) {
                // As for gzip, the failure is not reported by flush(). The filter has failed so the next write will
                // report it.
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("zstdOutputFilter.flushFail"), ioe);
                }
            }
        }
        buffer.flush();
    }


    @Override
    public void setResponse(Response response) {
        // Zstd has been chosen for this response, with this level
        level = CompressionConfig.getZstdLevel(response);
    }


    @Override
    public void setBuffer(HttpOutputBuffer buffer) {
        this.buffer = buffer;
    }


    @Override
    public void end() throws IOException {
        // The end of the response can be signalled more than once. The compressed stream must only be completed, and
        // so written, once.
        if (!completed) {
            if (failed) {
                // The response must not be ended as if it were complete. The client would be given an incomplete Zstd
                // stream as if it were the response. As for gzip, where completing the stream fails, report the failure
                // so that the response is aborted.
                throw new IOException(sm.getString("zstdOutputFilter.failed"));
            }
            try {
                compress(ByteBuffer.allocate(0), ZstdEncoder.FINISH);
                completed = true;
            } finally {
                release();
            }
        }
        buffer.end();
    }


    @Override
    public void recycle() {
        release();
        completed = false;
        failed = false;
    }


    /**
     * Release the encoder and prevent any more data being compressed. This is safe to call at any time, from any
     * thread, and more than once. It is for responses that do not end normally. A filter that is closed is not
     * recycled by HTTP/2 so it can not be used again.
     */
    @Override
    public void close() {
        failed = true;
        release();
    }


    private void compress(ByteBuffer input, int operation) throws IOException {
        ZstdEncoder current = encoder;
        if (current == null) {
            if (failed) {
                throw new IOException(sm.getString("zstdOutputFilter.failed"));
            }
            output = ByteBuffer.allocate(OUTPUT_SIZE);
            current = ZstdEncoder.create(level);
            encoder = current;
            if (failed) {
                // Closed while the encoder was being created
                release();
                throw new IOException(sm.getString("zstdOutputFilter.failed"));
            }
        }
        try {
            do {
                current.compress(input, output, operation);
                output.flip();
                if (output.hasRemaining()) {
                    buffer.doWrite(output);
                }
                output.clear();
            } while (input.hasRemaining() || current.hasMoreOutput() ||
                    operation == ZstdEncoder.FINISH && !current.isFinished());
        } catch (IllegalStateException e) {
            // The filter was closed by another thread while the encoder was being used
            failed = true;
            release();
            throw new IOException(sm.getString("zstdOutputFilter.failed"), e);
        } catch (IOException | RuntimeException e) {
            // The Zstd stream is not complete and the state of the encoder is not known so it can not be continued
            failed = true;
            release();
            throw e;
        }
    }


    private void release() {
        ZstdEncoder current = encoder;
        encoder = null;
        if (current != null) {
            current.close();
        }
    }
}
