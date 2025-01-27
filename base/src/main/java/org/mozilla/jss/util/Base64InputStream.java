/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.jss.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Arrays;

/**
 * Reads in base-64 encoded input and spits out the raw binary decoding.
 */
public class Base64InputStream extends FilterInputStream {

    private static final int WOULD_BLOCK = -2;

    //
    // decoding table
    //
    private static int[] table = new int[256];

    // one-time initialization of decoding table
    static {
        int i;
        for (i = 0; i < 256; ++i) {
            table[i] = -1;
        }
        int c;
        for (c = 'A', i = 0; c <= 'Z'; ++c, ++i) {
            table[c] = i;
        }
        for (c = 'a'; c <= 'z'; ++c, ++i) {
            table[c] = i;
        }
        for (c = '0'; c <= '9'; ++c, ++i) {
            table[c] = i;
        }
        table['+'] = 62;
        table['/'] = 63;
    }

    // prev is the previous significant character read from the in stream.
    // Significant characters are those that are part of the encoded data,
    // as opposed to whitespace.
    private int prev, savedPrev;

    // state is the current state of our state machine. The states are 1-5.
    // State 5 represents end-of-file, which occurs when we read the last
    // character from the input stream, or a base64 padding character ('=').
    // States 1-4 indicate which character of the current 4-character block we
    // are looking for. After state 4 we wrap back to state 1. The state
    // is not advanced when we read an insignificant character (such as
    // whitespace).
    private int state = 1, savedState;

    public Base64InputStream(InputStream in) {
        super(in);
    }

    @Override
    public long skip(long n) throws IOException {
        long count = 0;
        while ((count < n) && (read() != -1)) {
            ++count;
        }
        return count;
    }

    /**
     * param block Whether or not to block waiting for input.
     */
    private int read(boolean block) throws IOException {
        int cur, ret = 0;
        boolean done = false;
        while (!done) {
            if (in.available() < 1 && !block) {
                return WOULD_BLOCK;
            }
            cur = in.read();
            switch (state) {
            case 1:
                if (cur == -1) {
                    // end of file
                    state = 5;
                    return -1;
                }
                if (cur == '=') {
                    state = 5;
                    throw new IOException("Invalid pad character");
                }
                if (table[cur] != -1) {
                    prev = cur;
                    state = 2;
                }
                break;
            case 2:
                if (cur == -1) {
                    state = 5;
                    throw new EOFException("Unexpected end-of-file");
                }
                if (cur == '=') {
                    state = 5;
                    throw new IOException("Invalid pad character");
                }
                if (table[cur] != -1) {
                    ret = (table[prev] << 2) | ((table[cur] & 0x30) >> 4);
                    prev = cur;
                    state = 3;
                    done = true;
                }
                break;
            case 3:
                if (cur == -1) {
                    state = 5;
                    throw new EOFException("Unexpected end-of-file");
                }
                if (cur == '=') {
                    // pad character
                    state = 5;
                    return -1;
                }
                if (table[cur] != -1) {
                    ret = ((table[prev] & 0x0f) << 4) | ((table[cur] & 0x3c) >> 2);
                    prev = cur;
                    state = 4;
                    done = true;
                }
                break;
            case 4:
                if (cur == -1) {
                    state = 5;
                    throw new EOFException("Unexpected end-of-file");
                }
                if (cur == '=') {
                    // pad character
                    state = 5;
                    return -1;
                }
                if (table[cur] != -1) {
                    ret = ((table[prev] & 0x03) << 6) | table[cur];
                    state = 1;
                    done = true;
                }
                break;
            case 5:
                // end of file
                return -1;
            //break;
            default:
                assert (false);
                break;
            }
        }
        return ret;
    }

    @Override
    public int read() throws IOException {
        return read(true);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int count = 0;

        if (len < 0) {
            throw new IndexOutOfBoundsException("len is negative");
        }
        if (off < 0) {
            throw new IndexOutOfBoundsException("off is negative");
        }

        while (count < len) {
            int cur = read(count == 0);
            if (cur == -1) {
                // end-of-file
                return count == 0 ? -1 : count;
            }
            if (cur == WOULD_BLOCK) {
                assert (count > 0);
                return count;
            }
            assert (cur >= 0 && cur <= 255);
            b[off + (count++)] = (byte) cur;
        }
        return count;
    }

    @Override
    public int available() throws IOException {
        // We really don't know how much is left. in.available() could all
        // be whitespace.
        return 0;
    }

    @Override
    public boolean markSupported() {
        return in.markSupported();
    }

    @Override
    public void mark(int readlimit) {
        in.mark(readlimit);
        savedPrev = prev;
        savedState = state;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    @Override
    public void reset() throws IOException {
        in.reset();
        prev = savedPrev;
        state = savedState;
    }

    public static void main(String args[]) throws Exception {

        String infile = args[0];
        String b64file = infile.concat(".b64");
        String newfile = infile.concat(".recov");

        ByteArrayOutputStream origStream = new ByteArrayOutputStream();
        ByteArrayOutputStream b64OStream = new ByteArrayOutputStream();

        Base64OutputStream b64Stream = new Base64OutputStream(
                new PrintStream(b64OStream), 18);

        int numread;
        byte[] data = new byte[1024];

        try (FileInputStream fis = new FileInputStream(infile)) {
            while ((numread = fis.read(data, 0, 1024)) != -1) {
                origStream.write(data, 0, numread);
                b64Stream.write(data, 0, numread);
            }
        }

        b64Stream.close();
        origStream.close();

        ByteArrayOutputStream newStream = new ByteArrayOutputStream();

        ByteArrayInputStream bais = new ByteArrayInputStream(b64OStream.toByteArray());
        try (Base64InputStream bis = new Base64InputStream(bais)) {
            while ((numread = bis.read(data, 0, 1024)) != -1) {
                newStream.write(data, 0, numread);
            }
        }

        newStream.close();
        if (!Arrays.equals(origStream.toByteArray(), newStream.toByteArray())) {
            throw new Exception("Did not recover original data");
        }
    }
}
