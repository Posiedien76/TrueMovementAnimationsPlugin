package com.truetileanimationmovement;

import java.awt.image.BufferedImage;
import net.runelite.client.plugins.interfacestyles.InterfaceStylesPlugin;
import net.runelite.client.util.ImageUtil;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TrueMovementOverlayTest
{
	@Test
	public void interfaceStylesHealthBarAssetsAreAvailable()
	{
		BufferedImage Front = ImageUtil.loadImageResource(
				InterfaceStylesPlugin.class,
				"2010/healthbar/default_front_40px.png");
		BufferedImage Back = ImageUtil.loadImageResource(
				InterfaceStylesPlugin.class,
				"2010/healthbar/default_back_40px.png");

		assertEquals(40, Front.getWidth());
		assertEquals(7, Front.getHeight());
		assertEquals(Front.getWidth(), Back.getWidth());
		assertEquals(Front.getHeight(), Back.getHeight());
	}

	@Test
	public void interfaceStylesHealthBarUsesItsConfigValue()
	{
		assertTrue(TrueMovementOverlay
				.IsInterfaceStylesHdHealthBarEnabled("true"));
		assertTrue(TrueMovementOverlay
				.IsInterfaceStylesHdHealthBarEnabled("TRUE"));
		assertFalse(TrueMovementOverlay
				.IsInterfaceStylesHdHealthBarEnabled("false"));
		assertFalse(TrueMovementOverlay
				.IsInterfaceStylesHdHealthBarEnabled(null));
	}

    @Test
    public void highDetailHealthBarUsesNativePaddingAndClamping()
    {
        assertEquals(2,
                TrueMovementOverlay.GetHealthBarProgressFill(
                        40, 0.0f, 1));
        assertEquals(2,
                TrueMovementOverlay.GetHealthBarProgressFill(
                        40, 0.01f, 1));
        assertEquals(20,
                TrueMovementOverlay.GetHealthBarProgressFill(
                        40, 0.5f, 1));
        assertEquals(40,
                TrueMovementOverlay.GetHealthBarProgressFill(
                        40, 1.5f, 1));
    }

    @Test
    public void defaultHealthBarKeepsItsExistingUnpaddedFill()
    {
        assertEquals(0,
                TrueMovementOverlay.GetHealthBarProgressFill(
                        30, 0.0f, 0));
        assertEquals(15,
                TrueMovementOverlay.GetHealthBarProgressFill(
                        30, 0.5f, 0));
        assertEquals(30,
                TrueMovementOverlay.GetHealthBarProgressFill(
                        30, 1.5f, 0));
        assertEquals(0,
                TrueMovementOverlay.GetHealthBarProgressFill(
                        0, 0.5f, 1));
    }
}
