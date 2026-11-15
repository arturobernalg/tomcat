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
package org.apache.coyote.http11.filters.zstd;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.coyote.http11.filters.ZstdEncoder;

/**
 * Creates and closes a large number of encoders from several threads and checks that the resident memory of the process
 * does not grow with the number of encoders. It is not part of the unit tests as it takes some time, it is sensitive to
 * the system and it reads <code>/proc</code>, so it only works on Linux. Run it with a small heap, e.g.
 * <code>-Xmx128m</code>, so that garbage collection is frequent and the growth of the heap is not mistaken for native
 * memory that has not been released. The encoders are closed by the code that uses them, so the result does not depend
 * on the Cleaner.
 * <p>
 * Usage: <code>TesterZstdEncoderMemory [number of encoders [maximum growth in MB [level]]]</code>
 * <p>
 * The exit code is not zero if the resident memory grew by more than the maximum.
 */
public class TesterZstdEncoderMemory {

    public static void main(String[] args) throws Exception {
        int total = args.length > 0 ? Integer.parseInt(args[0]) : 400_000;
        long maximumGrowth = (args.length > 1 ? Long.parseLong(args[1]) : 100) * 1024;
        int level = args.length > 2 ? Integer.parseInt(args[2]) : 22;
        int threads = 16;
        byte[] data = "<p>Tomcat compresses this response with Zstd</p>\n".repeat(400).getBytes(StandardCharsets.UTF_8);

        // Let everything that is needed to run the loop be allocated first
        useEncoders(data, 2000, level);
        long before = residentMemory();
        long peak = before;

        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                try {
                    useEncoders(data, total / threads, level);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            workers[t].start();
        }
        for (Thread worker : workers) {
            while (worker.isAlive()) {
                worker.join(250);
                peak = Math.max(peak, residentMemory());
            }
        }
        long growth = peak - before;
        System.out.println(total + " encoders at level " + level + ", " + threads +
                " threads, resident memory grew by at most " + growth / 1024 + "MB (maximum " + maximumGrowth / 1024 +
                "MB)");
        if (growth > maximumGrowth) {
            System.exit(1);
        }
    }


    private static void useEncoders(byte[] data, int count, int level) throws IOException {
        ByteBuffer output = ByteBuffer.allocate(8192);
        for (int i = 0; i < count; i++) {
            try (ZstdEncoder encoder = ZstdEncoder.create(level)) {
                encoder.compress(ByteBuffer.wrap(data), output, ZstdEncoder.PROCESS);
                output.clear();
                do {
                    encoder.compress(ByteBuffer.allocate(0), output, ZstdEncoder.FINISH);
                    output.clear();
                } while (!encoder.isFinished());
            }
        }
    }


    private static long residentMemory() throws IOException {
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("VmRSS:")) {
                return Long.parseLong(line.replaceAll("\\D+", ""));
            }
        }
        throw new IOException("VmRSS not found");
    }
}
