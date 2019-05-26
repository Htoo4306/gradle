/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.hash;

import com.google.common.primitives.Ints;

import javax.annotation.Nullable;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * An immutable hash code. Must be 4-255 bytes long.
 * Inspired by the Google Guava project – https://github.com/google/guava.
 */
public abstract class HashCode implements Serializable, Comparable<HashCode> {
    private static final int MIN_NUMBER_OF_BYTES = 4;
    private static final int MAX_NUMBER_OF_BYTES = 255;
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private final int hashCode;

    protected HashCode(int hashCode) {
        this.hashCode = hashCode;
    }

    public static HashCode fromBytes(byte[] bytes) {
        // Make sure hash codes are serializable with a single byte length
        if (bytes.length < MIN_NUMBER_OF_BYTES || bytes.length > MAX_NUMBER_OF_BYTES) {
            throw new IllegalArgumentException(String.format("Invalid hash code length: %d bytes", bytes.length));
        }

        int hashCode = Arrays.hashCode(bytes);
        if (bytes.length == 16) {
            return new HashCode16(hashCode, bytes);
        } else {
            return new HashCodeN(hashCode, bytes);
        }
    }

    public static HashCode fromInt(int value) {
        byte[] bytes = Ints.toByteArray(value); // Big-endian
        return fromBytes(bytes);
    }

    public static HashCode fromString(String string) {
        int length = string.length();

        if (length % 2 != 0
            || length < MIN_NUMBER_OF_BYTES * 2
            || length > MAX_NUMBER_OF_BYTES * 2) {
            throw new IllegalArgumentException(String.format("Invalid hash code length: %d characters", length));
        }

        byte[] bytes = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            int ch1 = decode(string.charAt(i)) << 4;
            int ch2 = decode(string.charAt(i + 1));
            bytes[i / 2] = (byte) (ch1 + ch2);
        }

        return fromBytes(bytes);
    }

    private static int decode(char ch) {
        if (ch >= '0' && ch <= '9') {
            return ch - '0';
        }
        if (ch >= 'a' && ch <= 'f') {
            return ch - 'a' + 10;
        }
        if (ch >= 'A' && ch <= 'F') {
            return ch - 'A' + 10;
        }
        throw new IllegalArgumentException("Illegal hexadecimal character: " + ch);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public int compareTo(HashCode o) {
        return Integer.compare(hashCode, o.hashCode); // TODO why are we sorting based on a hash?
    }

    @Override
    public abstract boolean equals(@Nullable Object obj);

    public abstract int length();

    public abstract byte[] toByteArray();

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(2 * length());
        for (byte b : toByteArray()) {
            sb.append(HEX_DIGITS[(b >> 4) & 0xf]).append(HEX_DIGITS[b & 0xf]);
        }
        return sb.toString();
    }
}

class HashCode16 extends HashCode {
    private final long long1;
    private final long long2;

    HashCode16(int hashCode, byte[] bytes) {
        super(hashCode);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        this.long1 = buffer.getLong();
        this.long2 = buffer.getLong();
    }

    @Override
    public int length() {
        return 16;
    }

    public boolean equals(@Nullable Object obj) {
        if (obj == this) {
            return true;
        } else if (obj == null || obj.getClass() != HashCode16.class) {
            return false;
        }
        HashCode16 that = (HashCode16) obj;
        return long1 == that.long1
            && long2 == that.long2;
    }

    @Override
    public byte[] toByteArray() {
        return ByteBuffer.allocate(length())
            .putLong(long1)
            .putLong(long2)
            .array();
    }
}

class HashCodeN extends HashCode {
    private final byte[] bytes;

    HashCodeN(int hashCode, byte[] bytes) {
        super(hashCode);
        this.bytes = bytes.clone();
    }

    @Override
    public int length() {
        return bytes.length;
    }

    public boolean equals(@Nullable Object obj) {
        if (obj == this) {
            return true;
        } else if (obj == null || obj.getClass() != HashCodeN.class) {
            return false;
        }
        HashCodeN that = (HashCodeN) obj;
        return Arrays.equals(bytes, that.bytes);
    }

    public byte[] toByteArray() {
        return bytes.clone();
    }
}
