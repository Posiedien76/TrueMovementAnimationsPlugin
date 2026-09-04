package com.truetileanimationmovement;

import net.runelite.api.ColorTextureOverride;
import net.runelite.api.PlayerComposition;

import java.util.Arrays;

/**
 * Immutable identity for the geometry in a player composition.
 *
 * The client mutates its composition arrays in place, so a held model must
 * compare against copied values rather than retaining those arrays directly.
 */
final class PlayerAppearanceKey
{
	private final int Gender;
	private final int TransformedNpcId;
	private final int[] EquipmentIds;
	private final int[] Colors;
	private final short[][] ColorOverrides;
	private final short[][] TextureOverrides;

	private PlayerAppearanceKey(
			int Gender,
			int TransformedNpcId,
			int[] EquipmentIds,
			int[] Colors,
			short[][] ColorOverrides,
			short[][] TextureOverrides)
	{
		this.Gender = Gender;
		this.TransformedNpcId = TransformedNpcId;
		this.EquipmentIds = Copy(EquipmentIds);
		this.Colors = Copy(Colors);
		this.ColorOverrides = DeepCopy(ColorOverrides);
		this.TextureOverrides = DeepCopy(TextureOverrides);
	}

	static PlayerAppearanceKey From(PlayerComposition Composition)
	{
		if (Composition == null)
		{
			return null;
		}

		ColorTextureOverride[] Overrides =
				Composition.getColorTextureOverrides();
		short[][] ColorOverrides = null;
		short[][] TextureOverrides = null;
		if (Overrides != null)
		{
			ColorOverrides = new short[Overrides.length][];
			TextureOverrides = new short[Overrides.length][];
			for (int Index = 0; Index < Overrides.length; ++Index)
			{
				ColorTextureOverride Override = Overrides[Index];
				if (Override != null)
				{
					ColorOverrides[Index] =
							Override.getColorToReplaceWith();
					TextureOverrides[Index] =
							Override.getTextureToReplaceWith();
				}
			}
		}

		return new PlayerAppearanceKey(
				Composition.getGender(),
				Composition.getTransformedNpcId(),
				Composition.getEquipmentIds(),
				Composition.getColors(),
				ColorOverrides,
				TextureOverrides);
	}

	static PlayerAppearanceKey Of(
			int Gender,
			int TransformedNpcId,
			int[] EquipmentIds,
			int[] Colors,
			short[][] ColorOverrides,
			short[][] TextureOverrides)
	{
		return new PlayerAppearanceKey(
				Gender,
				TransformedNpcId,
				EquipmentIds,
				Colors,
				ColorOverrides,
				TextureOverrides);
	}

	boolean Matches(PlayerComposition Composition)
	{
		if (Composition == null ||
				Gender != Composition.getGender() ||
				TransformedNpcId != Composition.getTransformedNpcId() ||
				!Arrays.equals(
						EquipmentIds,
						Composition.getEquipmentIds()) ||
				!Arrays.equals(Colors, Composition.getColors()))
		{
			return false;
		}

		ColorTextureOverride[] Overrides =
				Composition.getColorTextureOverrides();
		int OverrideCount = Overrides == null ? 0 : Overrides.length;
		int CachedOverrideCount =
				ColorOverrides == null ? 0 : ColorOverrides.length;
		if (OverrideCount != CachedOverrideCount)
		{
			return false;
		}

		for (int Index = 0; Index < OverrideCount; ++Index)
		{
			ColorTextureOverride Override = Overrides[Index];
			short[] ColorsAtIndex = Override == null
					? null
					: Override.getColorToReplaceWith();
			short[] TexturesAtIndex = Override == null
					? null
					: Override.getTextureToReplaceWith();
			if (!Arrays.equals(ColorOverrides[Index], ColorsAtIndex) ||
					!Arrays.equals(TextureOverrides[Index], TexturesAtIndex))
			{
				return false;
			}
		}
		return true;
	}

	private static int[] Copy(int[] Values)
	{
		return Values == null
				? null
				: Arrays.copyOf(Values, Values.length);
	}

	private static short[][] DeepCopy(short[][] Values)
	{
		if (Values == null)
		{
			return null;
		}

		short[][] Copy = new short[Values.length][];
		for (int Index = 0; Index < Values.length; ++Index)
		{
			Copy[Index] = Values[Index] == null
					? null
					: Arrays.copyOf(Values[Index], Values[Index].length);
		}
		return Copy;
	}

	@Override
	public boolean equals(Object Other)
	{
		if (this == Other)
		{
			return true;
		}
		if (!(Other instanceof PlayerAppearanceKey))
		{
			return false;
		}

		PlayerAppearanceKey That = (PlayerAppearanceKey) Other;
		return Gender == That.Gender &&
				TransformedNpcId == That.TransformedNpcId &&
				Arrays.equals(EquipmentIds, That.EquipmentIds) &&
				Arrays.equals(Colors, That.Colors) &&
				Arrays.deepEquals(ColorOverrides, That.ColorOverrides) &&
				Arrays.deepEquals(TextureOverrides, That.TextureOverrides);
	}

	@Override
	public int hashCode()
	{
		int Result = 31 * Gender + TransformedNpcId;
		Result = 31 * Result + Arrays.hashCode(EquipmentIds);
		Result = 31 * Result + Arrays.hashCode(Colors);
		Result = 31 * Result + Arrays.deepHashCode(ColorOverrides);
		Result = 31 * Result + Arrays.deepHashCode(TextureOverrides);
		return Result;
	}
}
