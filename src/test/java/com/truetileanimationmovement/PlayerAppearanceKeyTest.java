package com.truetileanimationmovement;

import net.runelite.api.ColorTextureOverride;
import net.runelite.api.PlayerComposition;
import net.runelite.api.kit.KitType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class PlayerAppearanceKeyTest
{
	@Test
	public void appearanceKeyCopiesMutableCompositionArrays()
	{
		int[] Equipment = {1, 2, 3};
		int[] Colors = {4, 5};
		short[][] ColorOverrides = {{6, 7}};
		short[][] TextureOverrides = {{8, 9}};

		PlayerAppearanceKey Key = PlayerAppearanceKey.Of(
				0, -1, Equipment, Colors,
				ColorOverrides, TextureOverrides);
		PlayerAppearanceKey OriginalValues = PlayerAppearanceKey.Of(
				0, -1,
				new int[]{1, 2, 3},
				new int[]{4, 5},
				new short[][]{{6, 7}},
				new short[][]{{8, 9}});

		Equipment[0] = 100;
		Colors[0] = 101;
		ColorOverrides[0][0] = 102;
		TextureOverrides[0][0] = 103;

		assertEquals(OriginalValues, Key);
		assertNotEquals(PlayerAppearanceKey.Of(
				1, -1,
				new int[]{1, 2, 3},
				new int[]{4, 5},
				new short[][]{{6, 7}},
				new short[][]{{8, 9}}), Key);
	}

	@Test
	public void equalityAndHashIncludeEveryGeometryField()
	{
		PlayerAppearanceKey Baseline = PlayerAppearanceKey.Of(
				0, -1, new int[]{1}, new int[]{2},
				new short[][]{{3}}, new short[][]{{4}});
		PlayerAppearanceKey Equivalent = PlayerAppearanceKey.Of(
				0, -1, new int[]{1}, new int[]{2},
				new short[][]{{3}}, new short[][]{{4}});

		assertEquals(Baseline, Equivalent);
		assertEquals(Baseline.hashCode(), Equivalent.hashCode());
		assertNotEquals(Baseline, PlayerAppearanceKey.Of(
				1, -1, new int[]{1}, new int[]{2},
				new short[][]{{3}}, new short[][]{{4}}));
		assertNotEquals(Baseline, PlayerAppearanceKey.Of(
				0, 42, new int[]{1}, new int[]{2},
				new short[][]{{3}}, new short[][]{{4}}));
		assertNotEquals(Baseline, PlayerAppearanceKey.Of(
				0, -1, new int[]{9}, new int[]{2},
				new short[][]{{3}}, new short[][]{{4}}));
		assertNotEquals(Baseline, PlayerAppearanceKey.Of(
				0, -1, new int[]{1}, new int[]{9},
				new short[][]{{3}}, new short[][]{{4}}));
		assertNotEquals(Baseline, PlayerAppearanceKey.Of(
				0, -1, new int[]{1}, new int[]{2},
				new short[][]{{9}}, new short[][]{{4}}));
		assertNotEquals(Baseline, PlayerAppearanceKey.Of(
				0, -1, new int[]{1}, new int[]{2},
				new short[][]{{3}}, new short[][]{{9}}));
	}

	@Test
	public void matchesDetectsInPlaceCompositionMutation()
	{
		FakeOverride Override = new FakeOverride(
				new short[]{5}, new short[]{6});
		FakeComposition Composition = new FakeComposition(
				0, -1, new int[]{1, 2}, new int[]{3, 4},
				new ColorTextureOverride[]{Override});
		PlayerAppearanceKey Key = PlayerAppearanceKey.From(Composition);

		assertTrue(Key.Matches(Composition));
		Composition.EquipmentIds[0] = 99;
		assertFalse(Key.Matches(Composition));
		Composition.EquipmentIds[0] = 1;
		Override.Colors[0] = 99;
		assertFalse(Key.Matches(Composition));
	}

	@Test
	public void nullCompositionArraysRemainComparable()
	{
		assertEquals(
				PlayerAppearanceKey.Of(0, -1, null, null, null, null),
				PlayerAppearanceKey.Of(0, -1, null, null, null, null));
	}

	private static final class FakeOverride implements ColorTextureOverride
	{
		private final short[] Colors;
		private final short[] Textures;

		private FakeOverride(short[] Colors, short[] Textures)
		{
			this.Colors = Colors;
			this.Textures = Textures;
		}

		@Override
		public short[] getColorToReplaceWith()
		{
			return Colors;
		}

		@Override
		public short[] getTextureToReplaceWith()
		{
			return Textures;
		}
	}

	private static final class FakeComposition implements PlayerComposition
	{
		private final int Gender;
		private final int TransformedNpcId;
		private final int[] EquipmentIds;
		private final int[] Colors;
		private final ColorTextureOverride[] Overrides;

		private FakeComposition(
				int Gender,
				int TransformedNpcId,
				int[] EquipmentIds,
				int[] Colors,
				ColorTextureOverride[] Overrides)
		{
			this.Gender = Gender;
			this.TransformedNpcId = TransformedNpcId;
			this.EquipmentIds = EquipmentIds;
			this.Colors = Colors;
			this.Overrides = Overrides;
		}

		@Override
		public boolean isFemale()
		{
			return Gender == 1;
		}

		@Override
		public int getGender()
		{
			return Gender;
		}

		@Override
		public int[] getColors()
		{
			return Colors;
		}

		@Override
		public int[] getEquipmentIds()
		{
			return EquipmentIds;
		}

		@Override
		public int getEquipmentId(KitType Type)
		{
			return 0;
		}

		@Override
		public int getKitId(KitType Type)
		{
			return 0;
		}

		@Override
		public void setHash()
		{
		}

		@Override
		public int getTransformedNpcId()
		{
			return TransformedNpcId;
		}

		@Override
		public void setTransformedNpcId(int Id)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public ColorTextureOverride[] getColorTextureOverrides()
		{
			return Overrides;
		}

		@Override
		public ColorTextureOverride getColorTextureOverride(KitType Kit)
		{
			return null;
		}

		@Override
		public ColorTextureOverride createColorTextureOverride(
				KitType Kit,
				int ItemId)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public void removeColorTextureOverride(KitType Kit)
		{
		}
	}
}
