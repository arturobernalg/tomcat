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
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.Optional;

import org.apache.coyote.http11.filters.ZstdEncoder;
import org.apache.tomcat.util.res.StringManager;

/**
 * A {@link ZstdEncoder} that uses <code>libzstd</code> of the operating system.
 * <p>
 * The native memory used by this class is bounded. The input and output buffers are each {@value #BUFFER_SIZE} bytes
 * and the memory used by the library for the compression context is bounded, at any level, by the window size
 * ({@value #WINDOW_LOG} bits, well below the 8 MiB that <a href="https://www.rfc-editor.org/rfc/rfc9659">RFC 9659</a>
 * allows). All of it is released by {@link #close()}. If an encoder is abandoned without being closed, it is released
 * when the encoder is garbage collected. The memory is allocated and freed directly, rather than with an
 * {@link Arena}, because the memory of an automatic arena is not released until the garbage collector runs, which may
 * not be for some time as it does not see native memory, and closing a shared arena is expensive for every thread of
 * the JVM.
 * <p>
 * An encoder is not designed to be used by more than one thread at the same time but it can be used by different
 * threads one after the other. The methods are synchronized so that the native memory can not be used after it has
 * been released.
 * <p>
 * Once the library has reported an error the compression context is not used again.
 */
public final class FfmZstdEncoder implements ZstdEncoder {

    private static final StringManager sm = StringManager.getManager(FfmZstdEncoder.class);

    // Values from zstd.h
    private static final int PARAMETER_LEVEL = 100;
    private static final int PARAMETER_WINDOW_LOG = 101;
    private static final int PARAMETER_HASH_LOG = 102;
    private static final int PARAMETER_CHAIN_LOG = 103;
    private static final int PARAMETER_CHECKSUM = 201;
    private static final int END_CONTINUE = 0;
    private static final int END_FLUSH = 1;
    private static final int END_END = 2;

    // The base 2 logarithm of the size of the window. RFC 9659 requires the decoder to support 8 MiB (23) and
    // responses are usually small so a much smaller window is used. That also bounds the memory of the context.
    private static final int WINDOW_LOG = 18;

    // The sizes of the tables used to find matches. The library sizes them for the level, as if the response were
    // large, as the size of the response is not known. At the highest levels that is tens of megabytes for every
    // response being compressed. These are enough for the window and bound the memory to a few megabytes at any level,
    // for a very small difference in the size of the compressed response.
    private static final int HASH_LOG = 17;
    private static final int CHAIN_LOG = 17;

    private static final int BUFFER_SIZE = 16 * 1024;

    // ZSTD_inBuffer and ZSTD_outBuffer are each a pointer and two size_t values
    private static final long STRUCT_SIZE = 3L * Long.BYTES;

    private static final Cleaner CLEANER = Cleaner.create();

    private static final MethodHandle CREATE_CONTEXT;
    private static final MethodHandle FREE_CONTEXT;
    private static final MethodHandle SET_PARAMETER;
    private static final MethodHandle COMPRESS_STREAM;
    private static final MethodHandle IS_ERROR;
    private static final MethodHandle GET_ERROR_NAME;
    private static final MethodHandle MALLOC;
    private static final MethodHandle FREE;

    static {
        MethodHandle createContext = null;
        MethodHandle freeContext = null;
        MethodHandle setParameter = null;
        MethodHandle compressStream = null;
        MethodHandle isError = null;
        MethodHandle getErrorName = null;
        MethodHandle malloc = null;
        MethodHandle free = null;
        // size_t is handled as a long
        if (ValueLayout.ADDRESS.byteSize() == Long.BYTES) {
            SymbolLookup lookup = findLibrary();
            if (lookup != null) {
                Linker linker = Linker.nativeLinker();
                ValueLayout.OfInt i = ValueLayout.JAVA_INT;
                ValueLayout.OfLong l = ValueLayout.JAVA_LONG;
                ValueLayout a = ValueLayout.ADDRESS;
                createContext = downcall(linker, lookup, "ZSTD_createCCtx", FunctionDescriptor.of(a));
                freeContext = downcall(linker, lookup, "ZSTD_freeCCtx", FunctionDescriptor.of(l, a));
                setParameter = downcall(linker, lookup, "ZSTD_CCtx_setParameter", FunctionDescriptor.of(l, a, i, i));
                compressStream = downcall(linker, lookup, "ZSTD_compressStream2",
                        FunctionDescriptor.of(l, a, a, a, i));
                isError = downcall(linker, lookup, "ZSTD_isError", FunctionDescriptor.of(i, l));
                getErrorName = downcall(linker, lookup, "ZSTD_getErrorName", FunctionDescriptor.of(a, l));
                SymbolLookup libc = linker.defaultLookup();
                malloc = downcall(linker, libc, "malloc", FunctionDescriptor.of(a, l));
                free = downcall(linker, libc, "free", FunctionDescriptor.ofVoid(a));
            }
        }
        CREATE_CONTEXT = createContext;
        FREE_CONTEXT = freeContext;
        SET_PARAMETER = setParameter;
        COMPRESS_STREAM = compressStream;
        IS_ERROR = isError;
        GET_ERROR_NAME = getErrorName;
        MALLOC = malloc;
        FREE = free;
    }


    private static SymbolLookup findLibrary() {
        for (String name : new String[] { System.mapLibraryName("zstd"), "libzstd.so.1", "libzstd.1.dylib" }) {
            try {
                return SymbolLookup.libraryLookup(name, Arena.global());
            } catch (IllegalArgumentException | UnsatisfiedLinkError e) {
                // Try the next name
            }
        }
        return null;
    }


    private static MethodHandle downcall(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        Optional<MemorySegment> symbol = lookup.find(name);
        return symbol.map(s -> linker.downcallHandle(s, descriptor)).orElse(null);
    }


    /**
     * Is <code>libzstd</code> available?
     *
     * @return {@code true} if encoders can be created
     */
    public static boolean isAvailable() {
        return CREATE_CONTEXT != null && FREE_CONTEXT != null && SET_PARAMETER != null && COMPRESS_STREAM != null &&
                IS_ERROR != null && GET_ERROR_NAME != null && MALLOC != null && FREE != null;
    }


    // The native memory is released by close() or, if that is not called, by the Cleaner. Neither the Cleaner nor the
    // garbage collector are relied on for normal use as the amount of native memory that is held is not seen by the
    // garbage collector, which may therefore not run for some time.
    private final MemorySegment context;
    private final Cleaner.Cleanable cleanable;
    private final MemorySegment input;
    private final MemorySegment output;
    // ZSTD_inBuffer and ZSTD_outBuffer, which refer to the buffers above
    private final MemorySegment inBuffer;
    private final MemorySegment outBuffer;
    // The library reported an error so the context must not be used again
    private boolean failed;
    private boolean closed;
    // The library has more output for the last operation than fitted in the output buffer
    private boolean moreOutput;
    private boolean finished;


    /**
     * Create an encoder.
     *
     * @param level The compression level, from 1 (fastest) to 22 (smallest)
     *
     * @throws IOException If the encoder could not be created
     */
    public FfmZstdEncoder(int level) throws IOException {
        if (!isAvailable()) {
            throw new IOException(sm.getString("ffmZstdEncoder.unavailable"));
        }
        // What is allocated is released using the address that was returned
        MemorySegment newBuffers = MemorySegment.NULL;
        MemorySegment newContext = MemorySegment.NULL;
        try {
            // One block for the buffers and the structures
            long size = 2L * BUFFER_SIZE + 2L * STRUCT_SIZE;
            newBuffers = (MemorySegment) MALLOC.invoke(size);
            if (newBuffers.address() == 0) {
                throw new IOException(sm.getString("ffmZstdEncoder.createFail"));
            }
            MemorySegment buffers = newBuffers.reinterpret(size);
            input = buffers.asSlice(0, BUFFER_SIZE);
            output = buffers.asSlice(BUFFER_SIZE, BUFFER_SIZE);
            inBuffer = buffers.asSlice(2L * BUFFER_SIZE, STRUCT_SIZE);
            outBuffer = buffers.asSlice(2L * BUFFER_SIZE + STRUCT_SIZE, STRUCT_SIZE);
            newContext = (MemorySegment) CREATE_CONTEXT.invoke();
            if (MemorySegment.NULL.equals(newContext)) {
                throw new IOException(sm.getString("ffmZstdEncoder.createFail"));
            }
            setParameter(newContext, PARAMETER_LEVEL, level);
            setParameter(newContext, PARAMETER_WINDOW_LOG, WINDOW_LOG);
            setParameter(newContext, PARAMETER_HASH_LOG, HASH_LOG);
            setParameter(newContext, PARAMETER_CHAIN_LOG, CHAIN_LOG);
            // The checksum is not needed, the transport is already protected and the frame is smaller without it
            setParameter(newContext, PARAMETER_CHECKSUM, 0);
        } catch (Throwable t) {
            // Release whatever has been allocated. The Cleaner is not registered yet.
            new Destroyer(newContext, newBuffers).run();
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new IOException(sm.getString("ffmZstdEncoder.createFail"), t);
        }
        Cleaner.Cleanable newCleanable;
        try {
            newCleanable = CLEANER.register(this, new Destroyer(newContext, newBuffers));
        } catch (Throwable t) {
            new Destroyer(newContext, newBuffers).run();
            throw new IOException(sm.getString("ffmZstdEncoder.createFail"), t);
        }
        context = newContext;
        cleanable = newCleanable;
    }


    private static void setParameter(MemorySegment context, int parameter, int value) throws Throwable {
        long result = (long) SET_PARAMETER.invoke(context, parameter, value);
        if (isError(result)) {
            throw new IOException(sm.getString("ffmZstdEncoder.parameterFail", Integer.valueOf(parameter),
                    Integer.valueOf(value), errorName(result)));
        }
    }


    private static boolean isError(long result) throws Throwable {
        return ((int) IS_ERROR.invoke(result)) != 0;
    }


    private static String errorName(long result) {
        try {
            return ((MemorySegment) GET_ERROR_NAME.invoke(result)).reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            return "";
        }
    }


    @Override
    public synchronized void compress(ByteBuffer inputBuffer, ByteBuffer outputBuffer, int operation)
            throws IOException {
        if (closed) {
            throw new IOException(sm.getString("ffmZstdEncoder.closed"));
        }
        if (failed) {
            throw new IOException(sm.getString("ffmZstdEncoder.failed"));
        }
        if (finished) {
            if (inputBuffer.hasRemaining()) {
                throw new IOException(sm.getString("ffmZstdEncoder.finished"));
            }
            return;
        }
        int endDirective = switch (operation) {
            case PROCESS -> END_CONTINUE;
            case FLUSH -> END_FLUSH;
            case FINISH -> END_END;
            default -> throw new IllegalArgumentException(Integer.toString(operation));
        };
        int inputSize = Math.min(inputBuffer.remaining(), BUFFER_SIZE);
        int outputSize = Math.min(outputBuffer.remaining(), BUFFER_SIZE);
        MemorySegment.copy(MemorySegment.ofBuffer(inputBuffer), 0, input, 0, inputSize);
        inBuffer.set(ValueLayout.ADDRESS, 0, input);
        inBuffer.set(ValueLayout.JAVA_LONG, Long.BYTES, inputSize);
        inBuffer.set(ValueLayout.JAVA_LONG, 2L * Long.BYTES, 0);
        outBuffer.set(ValueLayout.ADDRESS, 0, output);
        outBuffer.set(ValueLayout.JAVA_LONG, Long.BYTES, outputSize);
        outBuffer.set(ValueLayout.JAVA_LONG, 2L * Long.BYTES, 0);
        long remaining;
        try {
            remaining = (long) COMPRESS_STREAM.invoke(context, outBuffer, inBuffer, endDirective);
            if (isError(remaining)) {
                failed = true;
                throw new IOException(sm.getString("ffmZstdEncoder.compressFail", errorName(remaining)));
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            failed = true;
            throw new IOException(sm.getString("ffmZstdEncoder.compressFail", t.toString()), t);
        } finally {
            // The context must not be freed by the Cleaner while the library is using it
            Reference.reachabilityFence(this);
        }
        int consumed = (int) inBuffer.get(ValueLayout.JAVA_LONG, 2L * Long.BYTES);
        int produced = (int) outBuffer.get(ValueLayout.JAVA_LONG, 2L * Long.BYTES);
        if (consumed > inputSize || produced > outputSize) {
            failed = true;
            throw new IOException(sm.getString("ffmZstdEncoder.compressFail", "invalid buffer position"));
        }
        if (outputSize > 0 && consumed == 0 && produced == 0 &&
                (operation == PROCESS ? inputSize > 0 : remaining != 0)) {
            // With room for output, the library must make progress. Otherwise the caller could loop for ever.
            failed = true;
            throw new IOException(sm.getString("ffmZstdEncoder.noProgress"));
        }
        inputBuffer.position(inputBuffer.position() + consumed);
        MemorySegment.copy(output, 0, MemorySegment.ofBuffer(outputBuffer), 0, produced);
        outputBuffer.position(outputBuffer.position() + produced);
        // For FLUSH and FINISH the value is the amount of data the library still has to flush, which is 0 when the
        // operation is complete. For PROCESS it is only a hint for the size of the next input.
        moreOutput = operation != PROCESS && remaining != 0;
        finished = operation == FINISH && remaining == 0 && consumed == inputSize && !inputBuffer.hasRemaining();
    }


    @Override
    public synchronized boolean hasMoreOutput() {
        checkOpen();
        return moreOutput;
    }


    @Override
    public synchronized boolean isFinished() {
        checkOpen();
        return finished;
    }


    @Override
    public synchronized void close() {
        closed = true;
        // Runs the Destroyer, if it has not been run, and only once
        cleanable.clean();
    }


    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException(sm.getString("ffmZstdEncoder.closed"));
        }
    }


    /**
     * Releases the native memory. This must not refer to the encoder, otherwise the encoder could not be garbage
     * collected and the Cleaner would not be able to release the memory of an abandoned encoder.
     */
    private static final class Destroyer implements Runnable {

        private final MemorySegment context;
        private final MemorySegment buffers;

        private Destroyer(MemorySegment context, MemorySegment buffers) {
            this.context = context;
            this.buffers = buffers;
        }

        @Override
        public void run() {
            try {
                if (!MemorySegment.NULL.equals(context)) {
                    FREE_CONTEXT.invoke(context);
                }
            } catch (Throwable t) {
                // Nothing that can be done
            }
            try {
                if (buffers.address() != 0) {
                    FREE.invoke(buffers);
                }
            } catch (Throwable t) {
                // Nothing that can be done
            }
        }
    }
}
