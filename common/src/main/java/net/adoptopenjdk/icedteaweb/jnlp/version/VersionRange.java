// Copyright (C) 2019 Karakun AG
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
//
package net.adoptopenjdk.icedteaweb.jnlp.version;

import net.adoptopenjdk.icedteaweb.Assert;

import java.util.Arrays;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static net.adoptopenjdk.icedteaweb.jnlp.version.JNLPVersionPatterns.REGEXP_VERSION_RANGE;
import static net.adoptopenjdk.icedteaweb.jnlp.version.VersionModifier.AMPERSAND;

/**
 * A version-range is either a version-id, a version-id followed by a star (*),
 * a version-id followed by a plus sign (+) , or two version-ranges combined using an ampersand (&amp;).
 * The star means prefix match, the plus sign means this version or greater,
 * and the ampersand means the logical and-ing of the two version-ranges.
 * <p>
 * The syntax of version-strings is:
 *
 * <pre>
 *      version-string     ::=  version-range ( " " element) *
 *      version-range      ::=  simple-range ( "&amp;" simple-range) *
 *      simple-range       ::=  version-id | version-id modifier
 *      modifier           ::=  "+" | "*"
 * </pre>
 * <p>
 * See JSR-56 Specification, Appendix A.
 *
 * @see JNLPVersionPatterns
 */
public class VersionRange {

    private final SimpleRange[] simpleRanges;

    private VersionRange(final SimpleRange[] simpleRanges) {
        this.simpleRanges = simpleRanges;
    }

    /**
     * @return {@code true} if this version-range is a compound range using an ampersand (&amp;), false otherwise
     */
    boolean isCompoundVersion() {
        return simpleRanges.length > 1;
    }

    /**
     * Checks whether this version-range represents a plain (exact) version without any postfix modifiers.
     *
     * @return {@code true} if this version-range does not have any modifiers, {@code false} otherwise.
     */
    boolean isExactVersion() {
        return simpleRanges.length == 1 && simpleRanges[0].isExactVersion();
    }

    /**
     * Checks whether this version-range represents a range with a prefix match modifier.
     *
     * @return {@code true} if this version-range is not a compound range
     * and its only simple range has a prefix match modifier, {@code false} otherwise.
     */
    boolean hasPrefixMatchModifier() {
        return simpleRanges.length == 1 && simpleRanges[0].hasPrefixMatchModifier();
    }

    /**
     * Checks whether this version-range represents a range with a greater or equal modifier.
     *
     * @return {@code true} if this version-range is not a compound range
     * and its only simple range has a greater or equal modifier, {@code false} otherwise.
     */
    boolean hasGreaterThanOrEqualMatchModifier() {
        return simpleRanges.length == 1 && simpleRanges[0].hasGreaterThanOrEqualMatchModifier();
    }

    /**
     * Construct a version-range by the given {@code versionRange}.
     *
     * @param versionRange a version-range
     * @return a version-id
     */
    public static VersionRange fromString(final String versionRange) {
        Assert.requireNonNull(versionRange, "versionRange");

        if (!versionRange.matches(REGEXP_VERSION_RANGE)) {
            throw new IllegalArgumentException(format("'%s' is not a valid version-range according to JSR-56, Appendix A.", versionRange));
        }

        final SimpleRange[] ranges = Arrays.stream(versionRange.split(AMPERSAND.symbol()))
                .map(SimpleRange::fromString)
                .toArray(SimpleRange[]::new);

        return new VersionRange(ranges);
    }

    /**
     * Check if this version-range contains the given {@code versionId}.
     *
     * @param versionId a version-id
     * @return {@code true} if this version-range contains {@code versionId}, {@code false} otherwise.
     */
    public boolean contains(final String versionId) {
        return contains(VersionId.fromString(versionId));
    }

    /**
     * Check if this version-range contains the given {@code versionId}.
     *
     * @param versionId a version-id
     * @return {@code true} if this version-range contains {@code versionId}, {@code false} otherwise.
     */
    public boolean contains(final VersionId versionId) {
        Assert.requireNonNull(versionId, "versionId");

        return Arrays.stream(simpleRanges).allMatch(simpleRange -> simpleRange.contains(versionId));
    }

    boolean isEqualTo(final VersionRange otherVersionRange) {
        return equals(otherVersionRange);
    }

    @Override
    public boolean equals(final Object otherVersionRange) {
        if (otherVersionRange == null || otherVersionRange.getClass() != VersionRange.class) {
            return false;
        }
        final VersionRange other = (VersionRange) otherVersionRange;

        return Arrays.equals(simpleRanges, other.simpleRanges);
    }

    /**
     * Provides a string representation of this {@link VersionRange}.
     *
     * @return a string representation of this version-range
     */
    @Override
    public String toString() {
        return Arrays.stream(simpleRanges)
                .map(SimpleRange::toString)
                .collect(Collectors.joining("&"));
    }
}
