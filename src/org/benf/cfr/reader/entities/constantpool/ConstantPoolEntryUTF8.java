package org.benf.cfr.reader.entities.constantpool;

import org.benf.cfr.reader.entities.AbstractConstantPoolEntry;
import org.benf.cfr.reader.util.bytestream.ByteData;
import org.benf.cfr.reader.util.getopt.Options;
import org.benf.cfr.reader.util.getopt.OptionsImpl;
import org.benf.cfr.reader.util.output.Dumper;

import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicInteger;

public class ConstantPoolEntryUTF8 extends AbstractConstantPoolEntry {
    private static final Charset UTF8_CHARSET = Charset.forName("UTF-8");

    private static final long OFFSET_OF_LENGTH = 1;
    private static final long OFFSET_OF_DATA = 3;

    private final int length;
    private final String value;

    private final static AtomicInteger idx = new AtomicInteger();

    public ConstantPoolEntryUTF8(ConstantPool cp, ByteData data, Options options) {
        super(cp);
        this.length = data.getU2At(OFFSET_OF_LENGTH);
        byte[] bytes = data.getBytesAt(length, OFFSET_OF_DATA);
        char[] outchars = new char[bytes.length];
        String tmpValue = null;
        int out = 0;
        try {
            for (int i = 0; i < bytes.length; ++i) {
                int x = bytes[i];
                if ((x & 0x80) == 0) {
                    outchars[out++] = (char) x;
                } else if ((x & 0xE0) == 0xC0) {
                    int y = bytes[++i];
                    if ((y & 0xC0) == 0x80) {
                        int val = ((x & 0x1f) << 6) + (y & 0x3f);
                        outchars[out++] = (char) val;
                    } else {
                        throw new IllegalArgumentException();
                    }
                } else if ((x & 0xF0) == 0xE0) {
                    int y = bytes[++i];
                    int z = bytes[++i];
                    if ((y & 0xC0) == 0x80 && (z & 0xC0) == 0x80) {
                        int val = ((x & 0xf) << 12) + ((y & 0x3f) << 6) + (z & 0x3f);
                        outchars[out++] = (char) val;
                    } else {
                        throw new IllegalArgumentException();
                    }
                } else {
                    throw new IllegalArgumentException();
                }
            }
            tmpValue = new String(outchars, 0, out);
        } catch (IllegalArgumentException ignore) {
        } catch (IndexOutOfBoundsException ignore) {
        }
        if (tmpValue == null) {
            // Constpool actually uses modified UTF8....
            tmpValue = new String(bytes, UTF8_CHARSET);
        }
        if (tmpValue.length() > 512 && options.getOption(OptionsImpl.HIDE_LONGSTRINGS)) {
            tmpValue = "longStr" + idx.getAndIncrement() + "[" + tmpValue.substring(0, 10).replace('\r', '_').replace('\n', '_') + "]";
        }
        this.value = tmpValue;
    }


    @Override
    public long getRawByteLength() {
        return 3 + length;
    }

    public String getValue() {
        return value;
    }

    @Override
    public void dump(Dumper d) {
        d.print("CONSTANT_UTF8 value=" + value);
    }

    @Override
    public String toString() {
        return "ConstantUTF8[" + value + "]";
    }
}
