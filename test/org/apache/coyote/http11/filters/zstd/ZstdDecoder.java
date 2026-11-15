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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * A decoder that uses <code>libzstd</code> directly, so that the tests do not depend on the code that is being tested.
 * <p>
 * As required by <a href="https://www.rfc-editor.org/rfc/rfc9659">RFC 9659</a> for HTTP, the decoder is configured to
 * refuse frames that need a window larger than 8 MiB.
 */
final class ZstdDecoder {

    // From zstd.h
    private static final int PARAMETER_WINDOW_LOG_MAX = 100;
    // 8 MiB, the limit of RFC 9659
    static final int HTTP_WINDOW_LOG_MAX = 23;

    private static final MethodHandle CREATE;
    private static final MethodHandle FREE;
    private static final MethodHandle SET_PARAMETER;
    private static final MethodHandle DECOMPRESS;
    private static final MethodHandle IS_ERROR;
    private static final MethodHandle GET_ERROR_NAME;

    static {
        MethodHandle create = null;
        MethodHandle free = null;
        MethodHandle setParameter = null;
        MethodHandle decompress = null;
        MethodHandle isError = null;
        MethodHandle getErrorName = null;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = SymbolLookup.libraryLookup(System.mapLibraryName("zstd"), Arena.global());
            ValueLayout a = ValueLayout.ADDRESS;
            ValueLayout.OfLong l = ValueLayout.JAVA_LONG;
            ValueLayout.OfInt i = ValueLayout.JAVA_INT;
            create = linker.downcallHandle(lookup.find("ZSTD_createDCtx").get(), FunctionDescriptor.of(a));
            free = linker.downcallHandle(lookup.find("ZSTD_freeDCtx").get(), FunctionDescriptor.of(l, a));
            setParameter = linker.downcallHandle(lookup.find("ZSTD_DCtx_setParameter").get(),
                    FunctionDescriptor.of(l, a, i, i));
            decompress = linker.downcallHandle(lookup.find("ZSTD_decompressStream").get(),
                    FunctionDescriptor.of(l, a, a, a));
            isError = linker.downcallHandle(lookup.find("ZSTD_isError").get(), FunctionDescriptor.of(i, l));
            getErrorName = linker.downcallHandle(lookup.find("ZSTD_getErrorName").get(), FunctionDescriptor.of(a, l));
        } catch (RuntimeException | LinkageError e) {
            // Not available
            create = null;
        }
        CREATE = create;
        FREE = free;
        SET_PARAMETER = setParameter;
        DECOMPRESS = decompress;
        IS_ERROR = isError;
        GET_ERROR_NAME = getErrorName;
    }


    static boolean isAvailable() {
        return CREATE != null;
    }


    /**
     * Decode a complete Zstd stream.
     */
    static byte[] decode(byte[] compressed) throws IOException {
        return decode(compressed, true);
    }


    /**
     * Decode as much of a Zstd stream as is present, which does not have to be complete.
     */
    static byte[] decodePartial(byte[] compressed) throws IOException {
        return decode(compressed, false);
    }


    /**
     * The window size that the first frame requires of a decoder, from the frame header, as defined by
     * <a href="https://www.rfc-editor.org/rfc/rfc8878#section-3.1.1.1">RFC 8878</a>, rather than from how the encoder
     * was configured.
     */
    static long windowSize(byte[] frame) throws IOException {
        if (frame.length < 6 || (frame[0] & 0xFF) != 0x28 || (frame[1] & 0xFF) != 0xB5 || (frame[2] & 0xFF) != 0x2F ||
                (frame[3] & 0xFF) != 0xFD) {
            throw new IOException("Not a Zstd frame");
        }
        int descriptor = frame[4] & 0xFF;
        boolean singleSegment = (descriptor & 0x20) != 0;
        int contentSizeFlag = descriptor >> 6;
        if (singleSegment) {
            // The window is the size of the content
            int size = switch (contentSizeFlag) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                default -> 8;
            };
            long contentSize = 0;
            for (int i = 0; i < size; i++) {
                contentSize |= (long) (frame[5 + i] & 0xFF) << (8 * i);
            }
            return contentSizeFlag == 1 ? contentSize + 256 : contentSize;
        }
        int window = frame[5] & 0xFF;
        long base = 1L << (10 + (window >> 3));
        return base + base / 8 * (window & 7);
    }


    private static boolean isError(long result) throws Throwable {
        return ((int) IS_ERROR.invoke(result)) != 0;
    }


    private static String errorName(long result) throws Throwable {
        return ((MemorySegment) GET_ERROR_NAME.invoke(result)).reinterpret(Long.MAX_VALUE).getString(0);
    }


    private static byte[] decode(byte[] compressed, boolean complete) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocate(Math.max(compressed.length, 1));
            MemorySegment.copy(MemorySegment.ofArray(compressed), 0, input, 0, compressed.length);
            MemorySegment output = arena.allocate(64 * 1024);
            // ZSTD_inBuffer and ZSTD_outBuffer
            MemorySegment inBuffer = arena.allocate(3L * Long.BYTES);
            MemorySegment outBuffer = arena.allocate(3L * Long.BYTES);
            inBuffer.set(ValueLayout.ADDRESS, 0, input);
            inBuffer.set(ValueLayout.JAVA_LONG, Long.BYTES, compressed.length);
            inBuffer.set(ValueLayout.JAVA_LONG, 2L * Long.BYTES, 0);
            MemorySegment context = (MemorySegment) CREATE.invoke();
            try {
                long status = (long) SET_PARAMETER.invoke(context, PARAMETER_WINDOW_LOG_MAX, HTTP_WINDOW_LOG_MAX);
                if (isError(status)) {
                    throw new IOException(errorName(status));
                }
                long remaining = 1;
                boolean frameStarted = false;
                while (true) {
                    outBuffer.set(ValueLayout.ADDRESS, 0, output);
                    outBuffer.set(ValueLayout.JAVA_LONG, Long.BYTES, output.byteSize());
                    outBuffer.set(ValueLayout.JAVA_LONG, 2L * Long.BYTES, 0);
                    long inPosition = inBuffer.get(ValueLayout.JAVA_LONG, 2L * Long.BYTES);
                    remaining = (long) DECOMPRESS.invoke(context, outBuffer, inBuffer);
                    if (isError(remaining)) {
                        throw new IOException("Zstd decode failed: " + errorName(remaining));
                    }
                    int produced = (int) outBuffer.get(ValueLayout.JAVA_LONG, 2L * Long.BYTES);
                    result.write(output.asSlice(0, produced).toArray(ValueLayout.JAVA_BYTE));
                    long inEnd = inBuffer.get(ValueLayout.JAVA_LONG, 2L * Long.BYTES);
                    frameStarted |= inEnd > 0;
                    if (remaining == 0) {
                        // The frame is complete and all of it has been output
                        break;
                    }
                    if (inEnd == compressed.length && produced < output.byteSize()) {
                        // All the input has been used and the decoder has no more output
                        break;
                    }
                    if (inEnd == inPosition && produced == 0) {
                        throw new IOException("Zstd decoder made no progress");
                    }
                }
                if (complete && remaining != 0) {
                    throw new IOException("Incomplete Zstd stream");
                }
                if (remaining == 0 && inBuffer.get(ValueLayout.JAVA_LONG, 2L * Long.BYTES) != compressed.length) {
                    throw new IOException("Data after the end of the Zstd stream");
                }
            } finally {
                FREE.invoke(context);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
        return result.toByteArray();
    }


    private ZstdDecoder() {
    }
}
