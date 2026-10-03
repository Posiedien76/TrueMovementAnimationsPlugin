package com.truetileanimationmovement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads .rlmesh v3 files written by obj_to_runelite_mesh_rlmesh_v3.h. */
public final class RuneliteMeshFile
{
    private static final byte[] MAGIC = {
            'R','L','M','E','S','H','0','3'
    };

    private static final long VERSION = 3L;

    public static final int FLAG_RUNELITE_COORDS = 1 << 0;
    public static final int FLAG_RUNELITE_NORMALS = 1 << 1;
    public static final int FLAG_JAGEX_HSL = 1 << 2;
    public static final int FLAG_REVERSED_WINDING = 1 << 3;
    public static final int FLAG_REORDERED_TO_TARGET = 1 << 4;
    public static final int FLAG_HAS_VERTEX_COLORS = 1 << 5;

    public final int flags;

    public final float[] vertexX;
    public final float[] vertexY;
    public final float[] vertexZ;

    public final float[] normalX;
    public final float[] normalY;
    public final float[] normalZ;

    public final int[] faceIndices1;
    public final int[] faceIndices2;
    public final int[] faceIndices3;

    public final int[] faceColors1;
    public final int[] faceColors2;
    public final int[] faceColors3;

    private RuneliteMeshFile(
            int flags,
            float[] vertexX,
            float[] vertexY,
            float[] vertexZ,
            float[] normalX,
            float[] normalY,
            float[] normalZ,
            int[] faceIndices1,
            int[] faceIndices2,
            int[] faceIndices3,
            int[] faceColors1,
            int[] faceColors2,
            int[] faceColors3)
    {
        this.flags = flags;
        this.vertexX = vertexX;
        this.vertexY = vertexY;
        this.vertexZ = vertexZ;
        this.normalX = normalX;
        this.normalY = normalY;
        this.normalZ = normalZ;
        this.faceIndices1 = faceIndices1;
        this.faceIndices2 = faceIndices2;
        this.faceIndices3 = faceIndices3;
        this.faceColors1 = faceColors1;
        this.faceColors2 = faceColors2;
        this.faceColors3 = faceColors3;
    }

    public int vertexCount()
    {
        return vertexX.length;
    }

    public int faceCount()
    {
        return faceIndices1.length;
    }

    public static RuneliteMeshFile read(Path path) throws IOException
    {
        return read(Files.readAllBytes(path));
    }

    public static RuneliteMeshFile read(InputStream input) throws IOException
    {
        return read(readAll(input));
    }

    public static RuneliteMeshFile read(byte[] bytes)
    {
        if (bytes.length < 32)
            throw new IllegalArgumentException("RLMESH file is truncated.");

        final ByteBuffer b = ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN);

        for (byte expected : MAGIC)
        {
            if (b.get() != expected)
                throw new IllegalArgumentException("Invalid RLMESH magic.");
        }

        final long version = uint32(b);
        if (version != VERSION)
            throw new IllegalArgumentException("Unsupported RLMESH version: " + version);

        final long flagsLong = uint32(b);
        final long vertexCountLong = uint32(b);
        final long faceCountLong = uint32(b);
        uint32(b); // reserved
        uint32(b); // reserved

        if (flagsLong > Integer.MAX_VALUE ||
                vertexCountLong > Integer.MAX_VALUE ||
                faceCountLong > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("Invalid RLMESH header.");
        }

        final int flags = (int) flagsLong;
        final int vertexCount = (int) vertexCountLong;
        final int faceCount = (int) faceCountLong;

        final long expectedSize =
                32L +
                        24L * vertexCount +
                        24L * faceCount;

        if (expectedSize != bytes.length)
        {
            throw new IllegalArgumentException(
                    "RLMESH size mismatch. Expected " + expectedSize +
                            " bytes, got " + bytes.length + ".");
        }

        final float[] vertexX = readFloats(b, vertexCount);
        final float[] vertexY = readFloats(b, vertexCount);
        final float[] vertexZ = readFloats(b, vertexCount);

        final float[] normalX = readFloats(b, vertexCount);
        final float[] normalY = readFloats(b, vertexCount);
        final float[] normalZ = readFloats(b, vertexCount);

        final int[] faceIndices1 = readInts(b, faceCount);
        final int[] faceIndices2 = readInts(b, faceCount);
        final int[] faceIndices3 = readInts(b, faceCount);

        final int[] faceColors1 = readInts(b, faceCount);
        final int[] faceColors2 = readInts(b, faceCount);
        final int[] faceColors3 = readInts(b, faceCount);

        for (int i = 0; i < faceCount; i++)
        {
            checkIndex(faceIndices1[i], vertexCount, i);
            checkIndex(faceIndices2[i], vertexCount, i);
            checkIndex(faceIndices3[i], vertexCount, i);
        }

        return new RuneliteMeshFile(
                flags,
                vertexX, vertexY, vertexZ,
                normalX, normalY, normalZ,
                faceIndices1, faceIndices2, faceIndices3,
                faceColors1, faceColors2, faceColors3);
    }

    private static float[] readFloats(ByteBuffer b, int count)
    {
        require(b, (long) count * Float.BYTES);
        final float[] out = new float[count];
        for (int i = 0; i < count; i++)
            out[i] = b.getFloat();
        return out;
    }

    private static int[] readInts(ByteBuffer b, int count)
    {
        require(b, (long) count * Integer.BYTES);
        final int[] out = new int[count];
        for (int i = 0; i < count; i++)
            out[i] = b.getInt();
        return out;
    }

    private static long uint32(ByteBuffer b)
    {
        require(b, 4);
        return Integer.toUnsignedLong(b.getInt());
    }

    private static void checkIndex(int index, int vertexCount, int face)
    {
        if (index < 0 || index >= vertexCount)
        {
            throw new IllegalArgumentException(
                    "Invalid vertex index " + index + " in face " + face + ".");
        }
    }

    private static void require(ByteBuffer b, long bytes)
    {
        if (bytes < 0 || bytes > b.remaining())
            throw new IllegalArgumentException("RLMESH file is truncated.");
    }

    private static byte[] readAll(InputStream input) throws IOException
    {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1)
            output.write(buffer, 0, n);
        return output.toByteArray();
    }
}
