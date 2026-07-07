package com.truetileanimationmovement;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class TrueTileMovementPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(TrueTileMovementPlugin.class);
		RuneLite.main(args);
	}
}