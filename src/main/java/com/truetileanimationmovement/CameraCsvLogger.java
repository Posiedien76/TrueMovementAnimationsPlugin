package com.truetileanimationmovement;

// TEMPORARY CAMERA DATA LOGGER

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.RuneLite;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

class CameraCsvLogger
{
	private static final DateTimeFormatter FILE_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US);
	private static final String HEADER = "FrameNumber,CameraFocalPointX,CameraFocalPointY,CameraFocalPointZ,CameraPitch,CameraYaw,PlayerModelX,PlayerModelZ,PlayerLocalX,PlayerLocalZ,DestinationLocalX,DestinationLocalZ,PlayerAnimation,IsMoving,CameraVelocityX,CameraVelocityZ,CameraVelocityMagnitude,CameraToPlayerModelDistanceXZ,CameraToPlayerLocalDistanceXZ,CameraToDestinationDistanceXZ";

	private final Path logDirectory = RuneLite.RUNELITE_DIR.toPath().resolve("camera_logs");

	private BufferedWriter writer;
	private boolean recording;
	private int frameNumber;
	private int framesSinceMovementStopped;
	private LocalPoint lastDestination;
	private Float lastCameraFocalPointX;
	private Float lastCameraFocalPointZ;

	void onBeforeRender(Client client, CustomMovementHandler playerMovementHandler)
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			stopRecording();
			return;
		}

		LocalPoint destination = client.getLocalDestinationLocation();
		boolean isMoving = destination != null && !destination.equals(player.getLocalLocation());

		if (!recording)
		{
			if (isMoving && lastDestination == null)
			{
				startRecording();
			}
		}

		if (!recording)
		{
			lastDestination = destination == null ? null : new LocalPoint(destination.getX(), destination.getY(), destination.getWorldView());
			return;
		}

		writeRow(client, player, playerMovementHandler, destination, isMoving);

		if (isMoving)
		{
			framesSinceMovementStopped = 0;
		}
		else
		{
			framesSinceMovementStopped++;
			if (framesSinceMovementStopped >= 60)
			{
				stopRecording();
			}
		}

		lastDestination = destination == null ? null : new LocalPoint(destination.getX(), destination.getY(), destination.getWorldView());
	}

	void shutDown()
	{
		stopRecording();
	}

	private void startRecording()
	{
		try
		{
			Files.createDirectories(logDirectory);
			Path file = logDirectory.resolve("camera_log_" + LocalDateTime.now().format(FILE_NAME_FORMAT) + ".csv");
			writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			writer.write(HEADER);
			writer.newLine();
			writer.flush();
			recording = true;
			frameNumber = 0;
			framesSinceMovementStopped = 0;
			lastCameraFocalPointX = null;
			lastCameraFocalPointZ = null;
		}
		catch (IOException e)
		{
			stopRecording();
		}
	}

	private void stopRecording()
	{
		recording = false;
		frameNumber = 0;
		framesSinceMovementStopped = 0;
		lastDestination = null;
		lastCameraFocalPointX = null;
		lastCameraFocalPointZ = null;
		if (writer != null)
		{
			try
			{
				writer.close();
			}
			catch (IOException ignored)
			{
			}
			writer = null;
		}
	}

	private void writeRow(Client client, Player player, CustomMovementHandler playerMovementHandler, LocalPoint destination, boolean isMoving)
	{
		if (writer == null)
		{
			return;
		}

		StringBuilder row = new StringBuilder();
		appendField(row, Integer.toString(frameNumber++));
		appendField(row, toField(client.getCameraFocalPointX()));
		appendField(row, toField(client.getCameraFocalPointY()));
		appendField(row, toField(client.getCameraFocalPointZ()));
		appendField(row, toField(client.getCameraPitch()));
		appendField(row, toField(client.getCameraYaw()));

		RuneLiteObject model = playerMovementHandler == null ? null : playerMovementHandler.Model;
		appendField(row, model != null && model.getLocation() != null ? toField(model.getLocation().getX()) : "");
		appendField(row, model != null && model.getLocation() != null ? toField(model.getLocation().getY()) : "");

		LocalPoint playerLocalLocation = player.getLocalLocation();
		appendField(row, playerLocalLocation != null ? toField(playerLocalLocation.getX()) : "");
		appendField(row, playerLocalLocation != null ? toField(playerLocalLocation.getY()) : "");
		appendField(row, destination != null ? toField(destination.getX()) : "");
		appendField(row, destination != null ? toField(destination.getY()) : "");
		appendField(row, toField(player.getAnimation()));
		appendField(row, isMoving ? "true" : "false");

		float cameraFocalPointX = client.getCameraFocalPointX();
		float cameraFocalPointZ = client.getCameraFocalPointZ();
		appendField(row, getVelocityField(cameraFocalPointX, lastCameraFocalPointX));
		appendField(row, getVelocityField(cameraFocalPointZ, lastCameraFocalPointZ));
		appendField(row, getVelocityMagnitudeField(cameraFocalPointX, lastCameraFocalPointX, cameraFocalPointZ, lastCameraFocalPointZ));
		appendField(row, getDistanceField(client.getCameraFocalPointX(), client.getCameraFocalPointZ(), model));
		appendField(row, getDistanceField(client.getCameraFocalPointX(), client.getCameraFocalPointZ(), playerLocalLocation));
		appendField(row, getDistanceField(client.getCameraFocalPointX(), client.getCameraFocalPointZ(), destination));

		lastCameraFocalPointX = cameraFocalPointX;
		lastCameraFocalPointZ = cameraFocalPointZ;

		try
		{
			writer.write(row.toString());
			writer.newLine();
			writer.flush();
		}
		catch (IOException e)
		{
			stopRecording();
		}
	}

	private static void appendField(StringBuilder row, String value)
	{
		if (row.length() > 0)
		{
			row.append(',');
		}
		row.append(value);
	}

	private static String toField(int value)
	{
		return Integer.toString(value);
	}

	private static String toField(float value)
	{
		return Float.toString(value);
	}

	private static String getVelocityField(float currentValue, Float previousValue)
	{
		return previousValue == null ? "" : Float.toString(currentValue - previousValue);
	}

	private static String getVelocityMagnitudeField(float currentX, Float previousX, float currentZ, Float previousZ)
	{
		if (previousX == null || previousZ == null)
		{
			return "";
		}

		float velocityX = currentX - previousX;
		float velocityZ = currentZ - previousZ;
		return Float.toString((float) Math.sqrt((velocityX * velocityX) + (velocityZ * velocityZ)));
	}

	private static String getDistanceField(float cameraFocalPointX, float cameraFocalPointZ, LocalPoint point)
	{
		if (point == null)
		{
			return "";
		}

		double dx = cameraFocalPointX - point.getX();
		double dz = cameraFocalPointZ - point.getY();
		return Double.toString(Math.sqrt((dx * dx) + (dz * dz)));
	}

	private static String getDistanceField(float cameraFocalPointX, float cameraFocalPointZ, RuneLiteObject object)
	{
		if (object == null || object.getLocation() == null)
		{
			return "";
		}

		double dx = cameraFocalPointX - object.getLocation().getX();
		double dz = cameraFocalPointZ - object.getLocation().getY();
		return Double.toString(Math.sqrt((dx * dx) + (dz * dz)));
	}
}
