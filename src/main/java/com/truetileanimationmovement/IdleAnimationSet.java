package com.truetileanimationmovement;

public class IdleAnimationSet
{
    public int IdleRotateLeft = 0;
    public int IdleRotateRight = 0;
    public int WalkAnimation = 0;
    public int WalkRotateLeft = 0;
    public int WalkRotateRight = 0;
    public int WalkRotate180 = 0;
    public int IdlePoseAnimation = 0;
    public int RunAnimation = 0;

    private String UniqueLabel;

    public void CacheUniqueLabel()
    {
        UniqueLabel = String.valueOf(IdleRotateLeft) +
                String.valueOf(IdleRotateRight) +
                String.valueOf(WalkAnimation) +
                String.valueOf(WalkRotateLeft) +
                String.valueOf(WalkRotateRight) +
                String.valueOf(WalkRotate180) +
                String.valueOf(IdlePoseAnimation) +
                String.valueOf(RunAnimation);
    }

    public String GetUniqueLabel()
    {
        return UniqueLabel;
    }
}
