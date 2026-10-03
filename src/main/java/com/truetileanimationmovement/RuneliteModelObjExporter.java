package com.truetileanimationmovement;

import net.runelite.api.Model;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Exports a RuneLite Model to OBJ with EXACTLY one OBJ vertex per RuneLite vertex.
 *
 * Output:
 *   v  X Y Z R G B
 *   vn X Y Z
 *   f  V1//N1 V2//N2 V3//N3
 *
 * The number of OBJ "v" lines is exactly model.getVerticesX().length.
 * Face indices are copied directly from RuneLite. No vertices are duplicated.
 *
 * Coordinate conversion:
 *   OBJ X = RL X
 *   OBJ Y = -RL Y
 *   OBJ Z = -RL Z
 *
 * RuneLite colors are per face corner, while OBJ vertex colors are per vertex.
 * To keep a strict 1:1 vertex count, each vertex gets the color from its
 * first face-corner use.
 */
public final class RuneliteModelObjExporter
{
    private RuneliteModelObjExporter()
    {
    }

    public static void export(Model model, Path output) throws IOException
    {
        export(model, output, true);
    }

    public static void export(
            Model model,
            Path output,
            boolean exportNormals) throws IOException
    {
        if (model == null)
        {
            throw new IllegalArgumentException("model cannot be null");
        }

        final float[] x = model.getVerticesX();
        final float[] y = model.getVerticesY();
        final float[] z = model.getVerticesZ();

        final int[] f1 = model.getFaceIndices1();
        final int[] f2 = model.getFaceIndices2();
        final int[] f3 = model.getFaceIndices3();

        if (x == null || y == null || z == null)
        {
            throw new IllegalArgumentException("Model is missing vertex data");
        }

        if (f1 == null || f2 == null || f3 == null)
        {
            throw new IllegalArgumentException("Model is missing face data");
        }

        if (x.length != y.length || x.length != z.length)
        {
            throw new IllegalArgumentException("Vertex arrays have different lengths");
        }

        if (f1.length != f2.length || f1.length != f3.length)
        {
            throw new IllegalArgumentException("Face index arrays have different lengths");
        }

        final int[] c1 = model.getFaceColors1();
        final int[] c2 = model.getFaceColors2();
        final int[] c3 = model.getFaceColors3();

        final boolean hasColors =
                c1 != null &&
                        c2 != null &&
                        c3 != null &&
                        c1.length == f1.length &&
                        c2.length == f1.length &&
                        c3.length == f1.length;

        final int[] nx = exportNormals ? model.getVertexNormalsX() : null;
        final int[] ny = exportNormals ? model.getVertexNormalsY() : null;
        final int[] nz = exportNormals ? model.getVertexNormalsZ() : null;

        final boolean hasNormals =
                nx != null &&
                        ny != null &&
                        nz != null &&
                        nx.length == x.length &&
                        ny.length == x.length &&
                        nz.length == x.length;

        /*
         * One color per OBJ vertex. -1 means no face has referenced it yet.
         */
        final int[] vertexColor = new int[x.length];
        Arrays.fill(vertexColor, -1);

        /*
         * Preserve the RuneLite vertex count.
         * Each vertex gets only its first encountered face-corner color.
         */
        if (hasColors)
        {
            for (int i = 0; i < f1.length; ++i)
            {
                assignFirstColor(vertexColor, f1[i], c1[i]);
                assignFirstColor(vertexColor, f2[i], c2[i]);
                assignFirstColor(vertexColor, f3[i], c3[i]);
            }
        }

        final StringBuilder obj =
                new StringBuilder(Math.max(4096, x.length * 60 + f1.length * 40));

        obj.append("# RuneLite vertex count: ")
                .append(x.length)
                .append('\n');

        obj.append("# RuneLite face count: ")
                .append(f1.length)
                .append('\n');

        obj.append("# Vertex count is exactly 1:1 with RuneLite Model.\n\n");

        // EXACTLY one OBJ vertex for every RuneLite vertex.
        for (int i = 0; i < x.length; ++i)
        {
            final float objX = x[i];
            final float objY = -y[i];
            final float objZ = -z[i];

            final int rgb =
                    vertexColor[i] == -1
                            ? 0xFFFFFF
                            : packedHslToRgb(vertexColor[i]);

            final float r = ((rgb >> 16) & 0xFF) / 255.0f;
            final float g = ((rgb >> 8) & 0xFF) / 255.0f;
            final float b = (rgb & 0xFF) / 255.0f;

            obj.append("v ")
                    .append(Float.toString(objX)).append(' ')
                    .append(Float.toString(objY)).append(' ')
                    .append(Float.toString(objZ)).append(' ')
                    .append(Float.toString(r)).append(' ')
                    .append(Float.toString(g)).append(' ')
                    .append(Float.toString(b))
                    .append('\n');
        }

        // EXACTLY one OBJ normal for every RuneLite vertex when available.
        if (hasNormals)
        {
            for (int i = 0; i < x.length; ++i)
            {
                obj.append("vn ")
                        .append(Float.toString(nx[i])).append(' ')
                        .append(Float.toString(-ny[i])).append(' ')
                        .append(Float.toString(-nz[i]))
                        .append('\n');
            }
        }

        /*
         * Faces are copied DIRECTLY from RuneLite.
         * RuneLite index 0 becomes OBJ index 1.
         * NO winding reversal.
         */
        for (int i = 0; i < f1.length; ++i)
        {
            validateVertexIndex(f1[i], x.length, i);
            validateVertexIndex(f2[i], x.length, i);
            validateVertexIndex(f3[i], x.length, i);

            final int a = f1[i] + 1;
            final int b = f2[i] + 1;
            final int c = f3[i] + 1;

            if (hasNormals)
            {
                obj.append("f ")
                        .append(a).append("//").append(a).append(' ')
                        .append(b).append("//").append(b).append(' ')
                        .append(c).append("//").append(c)
                        .append('\n');
            }
            else
            {
                obj.append("f ")
                        .append(a).append(' ')
                        .append(b).append(' ')
                        .append(c)
                        .append('\n');
            }
        }

        if (output.getParent() != null)
        {
            Files.createDirectories(output.getParent());
        }

        try (BufferedWriter writer = Files.newBufferedWriter(output))
        {
            writer.write(obj.toString());
        }
    }

    private static void assignFirstColor(
            int[] vertexColor,
            int vertex,
            int color)
    {
        if (vertex >= 0 &&
                vertex < vertexColor.length &&
                vertexColor[vertex] == -1)
        {
            vertexColor[vertex] = color;
        }
    }

    private static void validateVertexIndex(
            int index,
            int vertexCount,
            int faceIndex)
    {
        if (index < 0 || index >= vertexCount)
        {
            throw new IllegalArgumentException(
                    "Invalid vertex index " + index +
                            " in face " + faceIndex);
        }
    }

    /**
     * Approximate inverse conversion of RuneLite/Jagex packed HSL to RGB.
     */
    private static int packedHslToRgb(int packed)
    {
        final float h = ((packed >> 10) & 63) / 64.0f;
        final float s = ((packed >> 7) & 7) / 8.0f;
        final float l = (packed & 127) / 128.0f;

        if (s <= 0.0f)
        {
            final int v = clamp255(Math.round(l * 255.0f));
            return (v << 16) | (v << 8) | v;
        }

        final float q =
                l < 0.5f
                        ? l * (1.0f + s)
                        : l + s - l * s;

        final float p = 2.0f * l - q;

        final int r = clamp255(
                Math.round(hueToRgb(p, q, h + 1.0f / 3.0f) * 255.0f));

        final int g = clamp255(
                Math.round(hueToRgb(p, q, h) * 255.0f));

        final int b = clamp255(
                Math.round(hueToRgb(p, q, h - 1.0f / 3.0f) * 255.0f));

        return (r << 16) | (g << 8) | b;
    }

    private static float hueToRgb(float p, float q, float t)
    {
        if (t < 0.0f)
        {
            t += 1.0f;
        }

        if (t > 1.0f)
        {
            t -= 1.0f;
        }

        if (t < 1.0f / 6.0f)
        {
            return p + (q - p) * 6.0f * t;
        }

        if (t < 1.0f / 2.0f)
        {
            return q;
        }

        if (t < 2.0f / 3.0f)
        {
            return p + (q - p) *
                    (2.0f / 3.0f - t) * 6.0f;
        }

        return p;
    }

    private static int clamp255(int value)
    {
        return Math.max(0, Math.min(255, value));
    }
}
