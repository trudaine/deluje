/**
 * Bit-accurate Java port of the Deluge firmware.
 *
 * <h2>Number Type Mapping (C++ &rarr; Java)</h2>
 *
 * <p>Every numeric type in this package maps to its C++ counterpart with <strong>identical
 * bit-width and two's complement representation</strong>. Where the C++ type is unsigned and Java
 * has no direct equivalent, the standard Java idiom of <em>store as signed, widen with mask</em>
 * preserves bit-accuracy.
 *
 * <table border="1" style="border-collapse:collapse">
 * <caption>Canonical Type Mappings</caption>
 * <thead>
 *   <tr><th>C++ type</th><th>Java type</th><th>Notes</th></tr>
 * </thead>
 * <tbody>
 *   <tr>
 *     <td>{@code int32_t} / {@code int}</td>
 *     <td>{@code int}</td>
 *     <td>Direct 1:1 — both are signed 32-bit two's complement.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code uint32_t} / {@code unsigned int}</td>
 *     <td>{@code int}</td>
 *     <td>Same 32-bit pattern; read with {@code &amp; 0xFFFFFFFFL}
 *         (widening to {@code long}) if the consumer needs the unsigned
 *         arithmetic value. Many Deluge algorithms treat these as raw
 *         bitfields (Q31 fixed-point) where the sign bit is meaningful — in
 *         those cases the raw {@code int} is correct as-is.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code int16_t} / {@code short}</td>
 *     <td>{@code short}</td>
 *     <td>Direct 1:1 — both are signed 16-bit two's complement.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code uint16_t} / {@code unsigned short}</td>
 *     <td>{@code short}</td>
 *     <td>Store with explicit {@code (short)} cast (two's complement
 *         truncation matches C++ {@code uint16_t} storage). <br>
 *         <strong>Always read back with {@code val &amp; 0xFFFF}</strong>
 *         to widen to unsigned {@code int} (0–65535).<br>
 *         Example: {@code int value = table[i] &amp; 0xFFFF;}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code uint8_t} / {@code unsigned char}</td>
 *     <td>{@code byte} or {@code int}</td>
 *     <td>{@code byte} with {@code &amp; 0xFF} on read, or simply
 *         {@code int} — whichever is more readable. The 0–255 range fits
 *         comfortably in both.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code bool}</td>
 *     <td>{@code boolean}</td>
 *     <td>Direct 1:1.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code float}</td>
 *     <td>{@code float}</td>
 *     <td>IEEE 754 32-bit — identical bit pattern (Java strictfp is
 *         not required; JVM float arithmetic is already IEEE 754
 *         with {@code strictfp} only for legacy x87 80-bit excess
 *         precision, which does not apply here).</td>
 *   </tr>
 *   <tr>
 *     <td>{@code double}</td>
 *     <td>{@code double}</td>
 *     <td>IEEE 754 64-bit — identical. <br>
 *         <strong>Avoid unless C++ also uses {@code double}:</strong>
 *         the Deluge firmware almost exclusively uses 32-bit types.
 *         Use {@code float} for new fields unless the original code
 *         explicitly uses {@code double}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code enum}</td>
 *     <td>{@code enum}</td>
 *     <td>Direct 1:1. Java enums are reference types, not {@code int}
 *         like C++, but every enum in the firmware has the same ordinal
 *         numbering as its C++ counterpart. Use {@code ordinal()} and
 *         switch expressions where the original uses integer switch.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code T*}</td>
 *     <td>array or {@code List<T>}</td>
 *     <td>Fixed-size C arrays map to Java arrays; variable-size
 *         lists use {@code ArrayList}. Index math is preserved.</td>
 *   </tr>
 * </tbody>
 * </table>
 *
 * <h3>Bit-accuracy rule</h3>
 *
 * <p>A value is <strong>bit-accurate</strong> iff its 16/32/64-bit memory representation matches
 * the C++ original at every index. Widening with a mask ({@code &amp; 0xFFFF}) is acceptable
 * because it extracts the stored bits without altering them — it merely changes the interpretation
 * on read. Direct arithmetic on a {@code short} value that was stored as {@code uint16_t} (without
 * first masking) is <em>not</em> bit-accurate.
 *
 * <h3>Q31 fixed-point convention</h3>
 *
 * <p>The Deluge firmware uses Q31 (1.31 signed fixed-point) extensively. Values range from {@code
 * -2147483648} ({@code -1.0}) to {@code 2147483647} ({@code ≈1.0 - 2⁻³¹}). Multiplication is
 * performed via {@code multiply_32x32_rshift32()} which multiplies two Q31 values and right-shifts
 * by 32 to keep the result in Q31. This is identical in C++ and Java since both use 32-bit two's
 * complement for {@code int}.
 *
 * @see org.chuck.deluge.firmware.util.LookupTables
 */
package org.chuck.deluge.firmware;
