package com.truetileanimationmovement;

public class AnimationRequestDetails
{
    public double MovementSpeedMultiplier = 1;
    public int AnimationToPlay = -1;
    public int PoseAnimationToPlay = -1;
    public int StartingFrame = 0;
    public int EndingFrame = 5000;
    public int AnimationSpeed = 1;
    public int OrientationSpeed = 30;
    public boolean bResetAnimationOnNewTile = false;
    public boolean bUseLinearTween = false;
    public boolean bShouldTeleportToLocation = false;
    public boolean bAtDestinationLocation = false;

    static AnimationRequestDetails NewObject(AnimationRequestDetails InDetails)
    {
        AnimationRequestDetails newObject = new AnimationRequestDetails();

        newObject.MovementSpeedMultiplier = InDetails.MovementSpeedMultiplier;
        newObject.AnimationToPlay = InDetails.AnimationToPlay;
        newObject.PoseAnimationToPlay = InDetails.PoseAnimationToPlay;
        newObject.StartingFrame = InDetails.StartingFrame;
        newObject.EndingFrame = InDetails.EndingFrame;
        newObject.AnimationSpeed = InDetails.AnimationSpeed;
        newObject.OrientationSpeed = InDetails.OrientationSpeed;
        newObject.bResetAnimationOnNewTile = InDetails.bResetAnimationOnNewTile;
        newObject.bUseLinearTween = InDetails.bUseLinearTween;
        newObject.bShouldTeleportToLocation = InDetails.bShouldTeleportToLocation;
        newObject.bAtDestinationLocation = InDetails.bAtDestinationLocation;

        return newObject;
    }
}
