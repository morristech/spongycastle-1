package org.spongycastle.math.ec.custom.djb;

import java.math.BigInteger;

import org.spongycastle.math.ec.ECFieldElement;
import org.spongycastle.math.raw.Mod;
import org.spongycastle.math.raw.Nat256;
import org.spongycastle.util.Arrays;

public class Ed25519FieldElement extends ECFieldElement
{
    public static final BigInteger Q = Ed25519.q;

    // Calculated as ECConstants.TWO.modPow(Q.shiftRight(2), Q)
    private static final int[] PRECOMP_POW2 = new int[]{ 0x4a0ea0b0, 0xc4ee1b27, 0xad2fe478, 0x2f431806,
        0x3dfbd7a7, 0x2b4d0099, 0x4fc1df0b, 0x2b832480 };

    protected int[] x;

    public Ed25519FieldElement(BigInteger x)
    {
        if (x == null || x.signum() < 0 || x.compareTo(Q) >= 0)
        {
            throw new IllegalArgumentException("x value invalid for Ed25519FieldElement");
        }

        this.x = Ed25519Field.fromBigInteger(x);
    }

    public Ed25519FieldElement()
    {
        this.x = Nat256.create();
    }

    protected Ed25519FieldElement(int[] x)
    {
        this.x = x;
    }

    public boolean isZero()
    {
        return Nat256.isZero(x);
    }

    public boolean isOne()
    {
        return Nat256.isOne(x);
    }

    public boolean testBitZero()
    {
        return Nat256.getBit(x, 0) == 1;
    }

    public BigInteger toBigInteger()
    {
        return Nat256.toBigInteger(x);
    }

    public String getFieldName()
    {
        return "Ed25519Field";
    }

    public int getFieldSize()
    {
        return Q.bitLength();
    }

    public ECFieldElement add(ECFieldElement b)
    {
        int[] z = Nat256.create();
        Ed25519Field.add(x, ((Ed25519FieldElement)b).x, z);
        return new Ed25519FieldElement(z);
    }

    public ECFieldElement addOne()
    {
        int[] z = Nat256.create();
        Ed25519Field.addOne(x, z);
        return new Ed25519FieldElement(z);
    }

    public ECFieldElement subtract(ECFieldElement b)
    {
        int[] z = Nat256.create();
        Ed25519Field.subtract(x, ((Ed25519FieldElement)b).x, z);
        return new Ed25519FieldElement(z);
    }

    public ECFieldElement multiply(ECFieldElement b)
    {
        int[] z = Nat256.create();
        Ed25519Field.multiply(x, ((Ed25519FieldElement)b).x, z);
        return new Ed25519FieldElement(z);
    }

    public ECFieldElement divide(ECFieldElement b)
    {
        int[] z = Nat256.create();
        Mod.invert(Ed25519Field.P, ((Ed25519FieldElement)b).x, z);
        Ed25519Field.multiply(z, x, z);
        return new Ed25519FieldElement(z);
    }

    public ECFieldElement negate()
    {
        int[] z = Nat256.create();
        Ed25519Field.negate(x, z);
        return new Ed25519FieldElement(z);
    }

    public ECFieldElement square()
    {
        int[] z = Nat256.create();
        Ed25519Field.square(x, z);
        return new Ed25519FieldElement(z);
    }

    public ECFieldElement invert()
    {
//        return new Ed25519FieldElement(toBigInteger().modInverse(Q));
        int[] z = Nat256.create();
        Mod.invert(Ed25519Field.P, x, z);
        return new Ed25519FieldElement(z);
    }

    /**
     * return a sqrt root - the routine verifies that the calculation returns the right value - if
     * none exists it returns null.
     */
    public ECFieldElement sqrt()
    {
        /*
         * Q == 8m + 5, so we use Pocklington's method for this case.
         *
         * First, raise this element to the exponent 2^252 - 2^1 (i.e. m + 1)
         *
         * Breaking up the exponent's binary representation into "repunits", we get:
         * { 251 1s } { 1 0s }
         *
         * Therefore we need an addition chain containing 251 (the lengths of the repunits)
         * We use: 1, 2, 3, 4, 7, 11, 15, 30, 60, 120, 131, [251]
         */

        int[] x1 = this.x;
        if (Nat256.isZero(x1) || Nat256.isOne(x1))
        {
            return this;
        }

        int[] x2 = Nat256.create();
        Ed25519Field.square(x1, x2);
        Ed25519Field.multiply(x2, x1, x2);
        int[] x3 = x2;
        Ed25519Field.square(x2, x3);
        Ed25519Field.multiply(x3, x1, x3);
        int[] x4 = Nat256.create();
        Ed25519Field.square(x3, x4);
        Ed25519Field.multiply(x4, x1, x4);
        int[] x7 = Nat256.create();
        Ed25519Field.squareN(x4, 3, x7);
        Ed25519Field.multiply(x7, x3, x7);
        int[] x11 = x3;
        Ed25519Field.squareN(x7, 4, x11);
        Ed25519Field.multiply(x11, x4, x11);
        int[] x15 = x7;
        Ed25519Field.squareN(x11, 4, x15);
        Ed25519Field.multiply(x15, x4, x15);
        int[] x30 = x4;
        Ed25519Field.squareN(x15, 15, x30);
        Ed25519Field.multiply(x30, x15, x30);
        int[] x60 = x15;
        Ed25519Field.squareN(x30, 30, x60);
        Ed25519Field.multiply(x60, x30, x60);
        int[] x120 = x30;
        Ed25519Field.squareN(x60, 60, x120);
        Ed25519Field.multiply(x120, x60, x120);
        int[] x131 = x60;
        Ed25519Field.squareN(x120, 11, x131);
        Ed25519Field.multiply(x131, x11, x131);
        int[] x251 = x11;
        Ed25519Field.squareN(x131, 120, x251);
        Ed25519Field.multiply(x251, x120, x251);

        int[] t1 = x251;
        Ed25519Field.square(t1, t1);

        int[] t2 = x120;
        Ed25519Field.square(t1, t2);

        if (Nat256.eq(x1, t2))
        {
            return new Ed25519FieldElement(t1);
        }

        /*
         * If the first guess is incorrect, we multiply by a precomputed power of 2 to get the second guess,
         * which is ((4x)^(m + 1))/2 mod Q
         */
        Ed25519Field.multiply(t1, PRECOMP_POW2, t1);

        Ed25519Field.square(t1, t2);

        if (Nat256.eq(x1, t2))
        {
            return new Ed25519FieldElement(t1);
        }

        return null;
    }

    public boolean equals(Object other)
    {
        if (other == this)
        {
            return true;
        }

        if (!(other instanceof Ed25519FieldElement))
        {
            return false;
        }

        Ed25519FieldElement o = (Ed25519FieldElement)other;
        return Nat256.eq(x, o.x);
    }

    public int hashCode()
    {
        return Q.hashCode() ^ Arrays.hashCode(x, 0, 8);
    }
}