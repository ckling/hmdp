/*
 * Copyright (c) 2005-2006 Gregor Heinrich. All rights reserved. Redistribution and
 * use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met: 1. Redistributions of source
 * code must retain the above copyright notice, this list of conditions and the
 * following disclaimer. 2. Redistributions in binary form must reproduce the
 * above copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the distribution.
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESSED OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.gesis.promoss.tools.probabilistic;

import java.util.Arrays;

/**
 * ExpDouble represents a double-precision number by a mantissa, a decimal
 * exponent and the number of digits in the mantissa, in order to allow
 * formatting of the represented double.
 * 
 * @author gregor
 */
public class ExpDouble {

    private static double log10 = Math.log(10);

    public static void main(String[] args) {
        double[] x = new double[] {0, -1.0099638, 3.56, 30, -300.04, -45383487,
            9.998, 34598E12};
        for (int i = 0; i < x.length; i++) {
            ExpDouble a = new ExpDouble(x[i], 5);
            a.round(3);
            System.out.println(a.debug());
            a.round(1);
            System.out.println(a.toExpString());
        }

    }

    /**
     * Creates an ExpDouble from a double, with the specified number of digits.
     * 
     * @param x
     * @param maxdigits
     */
    public ExpDouble(double x, int maxdigits) {
        if (x == 0) {
            x = Math.pow(10, -maxdigits - 1);
            magnitude = -1;
            maxdigits = 1;
        } else {
            magnitude = orderOfMagnitude(x);
        }
        formatExponential(x, maxdigits);
        value = x;
    }

    /**
     * mantissa of number (unsigned)
     */
    long mantissa;
    /**
     * exponent of number
     */
    int exponent;
    /**
     * digits in mantissa
     */
    int digits;
    /**
     * order of magnitude of represented double
     */
    int magnitude;

    /**
     * the original value;
     */
    double value;

    /**
     * negative mantissa
     */
    boolean negative;

    /**
     * Round this instance to the number of significant digits
     * 
     * @param digits
     * @return whether magnitude and digits had to be changed by rounding values
     *         >9.5 to 10
     */
    public boolean round(int ndigits) {
        int diff = digits - ndigits;
        if (diff <= 0) {
            return false;
        }

        // mantissa: ###$ -> ###.$ -> ###
        mantissa = Math.round((double) mantissa / Math.pow(10, diff));
        exponent += diff;
        digits -= diff;
        // case >9.5 -> 10
        if (orderOfMagnitude(mantissa) >= digits) {
            mantissa /= 10;
            exponent++;
            // magnitude changes because we are interested in formatting.
            // the represented value is actually one order below
            magnitude++;
            return true;
        }
        return false;
    }

    /**
     * @return length as a string
     */
    int strlen() {
        if (Double.isInfinite(value)) {
            return negative ? 4: 3;
        } else if (Double.isNaN(value)) {
                return 3;
        }
        int len = digits;
        // minus sign
        if (negative) {
            len++;
        }
        // contains fractional part
        if (exponent < 0) {
            // point
            len++;
            // trailing zeros
            int tz = -exponent - digits;
            if (tz >= 0) {
                len += tz;
                // 0 left of . as additional char
                len++;
            }
        } else {
            len += exponent;
        }
        return len;
    }

    int strlenexp() {
        if (Double.isInfinite(value)) {
            return negative ? 4: 3;
        } else if (Double.isNaN(value)) {
                return 3;
        }
        int len = minstrlenexp();
        if (digits == 1) {
            return len;
        }
        // digits + point - 1 digit from minlen
        return minstrlenexp() + digits;
    }

    /**
     * @return minimum length of the number in exponential notation 1E3
     */
    int minstrlenexp() {
        if (Double.isInfinite(value)) {
            return negative ? 4: 3;
        } else if (Double.isNaN(value)) {
                return 3;
        }
        // digit and E3
        int len = 1 + 2;
        if (negative) {
            // minus sign
            len++;
        }
        if (value < 1) {
            // neg exponent
            len++;
        }
        if (value < 1E-10 || value > 1E10) {
            // additional exponent digit
            len++;
        }
        return len;
    }

    /**
     * @return string representation of this object
     */
    public String toString() {
        if (Double.isInfinite(value)) {
            return negative ? "-inf" : "inf";
        } else if (Double.isNaN(value)) {
            return "nan";
        }
        String[] pp = parts();
        if (pp[1] == null) {
            return pp[0];
        }
        return new StringBuilder().append(pp[0]).append(".").append(pp[1])
            .toString();
    }

    /**
     * @return a string representation in exponential notation
     */
    public String toExpString() {
        // #### E 5 = #.### E 5+(digits-1=3)
        // ### E -2 = #.## E -2+(digits-1=2)
        double factor = Math.pow(10, digits - 1);
        double m = mantissa / factor;
        String s = negative ? "-" : "";
        if (digits == 1) {
            s += Long.toString((long) m);
        } else {
            s += Double.toString(m);
        }
        s = s + "E" + (exponent + digits - 1);
        return s;
    }

    /**
     * @return a debug string with the part of this ExpDouble
     */
    public String debug() {
        return toString() + " = " + mantissa + "E" + exponent + " (" + "neg="
            + negative + ",digits=" + digits + ",magnitude=" + magnitude
            + ",strlen=" + strlen() + ")";
    }

    /**
     * @return double representation of this object according to rounding.
     */
    public double toDoubleUnsigned() {
        return mantissa * Math.pow(10, exponent);
    }

    /**
     * @return double representation of this object according to rounding.
     */
    public double toDouble() {
        double x = toDouble();
        return negative ? -x : x;
    }

    /**
     * @return string array of the integer and fraction parts
     */
    protected String[] parts() {

        String[] parts = new String[2];
        // integer part
        parts[0] = Long.toString((long) toDoubleUnsigned());
        // fractional part
        StringBuffer frac = new StringBuffer();
        if (exponent < 0) {
            int trailingZeros = -exponent - digits;
            int zerotail = 0;
            char[] zz;
            if (trailingZeros > 0) {
                zz = zeros(trailingZeros);
                frac.append(zz).append(mantissa);
            } else {
                String s = Long.toString(mantissa).substring(
                    Math.abs(trailingZeros));
                frac.append(s);
                zerotail = digits - (s.length() - trailingZeros);
            }
            // pad tailing zeros if they belong to digits
            // #.###00
            if (zerotail > 0) {
                zz = zeros(zerotail);
                frac.append(zz);
            }
            parts[1] = frac.toString();

        }
        if (negative) {
            parts[0] = "-" + parts[0];
        }
        return parts;
    }

    /**
     * Creates a character array with zeros.
     * 
     * @param count
     * @return
     */
    private char[] zeros(int count) {
        char[] zz;
        zz = new char[count];
        Arrays.fill(zz, '0');
        return zz;
    }

    /**
     * formats the number into a mantissa with interval in interval
     * [1,10)E{ndigits} and an exponent.
     * 
     * @param x the number
     * @param ndigits the number of significant digits
     */
    protected boolean formatExponential(double x, int ndigits) {

        boolean magchg = false;
        exponent = magnitude - ndigits + 1;
        digits = ndigits;
        double factor = Math.pow(10, -exponent);
        mantissa = (long) Math.abs(Math.round(x * factor));

        // case >9.5 -> 10
        if (orderOfMagnitude(mantissa) >= digits) {
            mantissa /= 10;
            exponent++;
            // magnitude changes because we are interested in formatting.
            // the represented value is actually one order below
            magnitude++;
            magchg = true;
        }

        if (x < 0) {
            negative = true;
        }

        // significant digits < max number of digits:
        // this does x=1803,digits=3 => 18E2 with only 2 digits
        // and not 3
        // while ((mantissa % 10) == 0) {
        // digits--;
        // mantissa /= 10;
        // exponent++;
        // }

        return magchg;
    }

    /**
     * @param x
     * @return order of magnitue of x, i.e., n : x in [ 10^n, 10^(n+1) )
     */
    public static int orderOfMagnitude(double x) {
        if (x == 0)
            return -Integer.MAX_VALUE;
        x = Math.abs(x);
        int magnitude = (int) (Math.log(x + 1E-12) / log10);
        if (x < 1)
            magnitude--;
        return magnitude;
    }

}
