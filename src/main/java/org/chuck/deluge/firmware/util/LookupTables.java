package org.chuck.deluge.firmware.util;

public class LookupTables {

  /**
   * Firmware note-to-note-interval table (12 semitones, each as a Q31 ratio relative to C). Used by
   * the pitch-adjust oscillator path: multiply_32x32_rshift32(table[noteWithinOctave],
   * pitchAdjustNeutralValue) then octave shift (13 - octave). Exists for future bit-exact alignment
   * of {@link org.chuck.deluge.firmware.engine.FirmwareSound#noteToPhaseInc}.
   */
  public static final int[] noteIntervalTable = {
    1073741824, 1137589835, 1205234447, 1276901417, 1352829926, 1433273380,
    1518500250, 1608794974, 1704458901, 1805811301, 1913190429, 2026954652,
  };

  /** Firmware note-to-frequency table (phase increments for sample sources, 20-octave shift). */
  public static final int[] noteFrequencyTable = {
    1027294024, 1088380105, 1153098554, 1221665363, 1294309365, 1371273005,
    1452813141, 1539201906, 1630727614, 1727695724, 1830429858, 1939272882,
  };

  public static final int[] tanTable = {
    0, 6040817, 12087756, 18146962, 24224633, 30327039, 36460554, 42631679,
    48847074, 55113581, 61438264, 67828436, 74291696, 80835969, 87469544, 94201124,
    101039871, 107995463, 115078152, 122298831, 129669107, 137201382, 144908946, 152806078,
    160908164, 169231824, 177795063, 186617437, 195720245, 205126751, 214862435, 224955281,
    235436122, 246339020, 257701735, 269566249, 281979405, 294993645, 308667898, 323068637,
    338271146, 354361056, 371436208, 389608933, 409008861, 429786415, 452117178, 476207408,
    502301079, 530688941, 561720325, 595818723, 633502600, 675413648, 722355745, 775349708,
    835711820, 905169129, 986033315, 1081471022, 1195939485, 1335918723, 1511206022, 1737350377,
    2040599645
  };

  public static final short[] decayTableSmall8 = {
    (short) 65535, (short) 64125, (short) 62746, (short) 61396, (short) 60075, (short) 58782,
        (short) 57517, (short) 56279, (short) 55067, (short) 53882, (short) 52722, (short) 51586,
    (short) 50476, (short) 49388, (short) 48325, (short) 47284, (short) 46265, (short) 45268,
        (short) 44293, (short) 43338, (short) 42404, (short) 41490, (short) 40595, (short) 39720,
    (short) 38863, (short) 38025, (short) 37205, (short) 36402, (short) 35616, (short) 34848,
        (short) 34095, (short) 33359, (short) 32639, (short) 31934, (short) 31244, (short) 30569,
    (short) 29909, (short) 29262, (short) 28630, (short) 28011, (short) 27405, (short) 26812,
        (short) 26232, (short) 25665, (short) 25109, (short) 24566, (short) 24034, (short) 23513,
    (short) 23004, (short) 22506, (short) 22018, (short) 21540, (short) 21073, (short) 20616,
        (short) 20169, (short) 19731, (short) 19303, (short) 18884, (short) 18474, (short) 18072,
    (short) 17680, (short) 17295, (short) 16919, (short) 16551, (short) 16191, (short) 15839,
        (short) 15494, (short) 15156, (short) 14826, (short) 14503, (short) 14186, (short) 13877,
    (short) 13574, (short) 13278, (short) 12988, (short) 12704, (short) 12426, (short) 12154,
        (short) 11888, (short) 11628, (short) 11373, (short) 11124, (short) 10880, (short) 10642,
    (short) 10408, (short) 10180, (short) 9956, (short) 9737, (short) 9523, (short) 9313,
        (short) 9108, (short) 8908, (short) 8711, (short) 8519, (short) 8331, (short) 8147,
    (short) 7967, (short) 7791, (short) 7618, (short) 7450, (short) 7284, (short) 7123,
        (short) 6965, (short) 6810, (short) 6659, (short) 6510, (short) 6365, (short) 6223,
    (short) 6085, (short) 5949, (short) 5816, (short) 5686, (short) 5558, (short) 5434,
        (short) 5312, (short) 5192, (short) 5076, (short) 4961, (short) 4850, (short) 4740,
    (short) 4633, (short) 4528, (short) 4426, (short) 4325, (short) 4227, (short) 4131,
        (short) 4037, (short) 3945, (short) 3855, (short) 3767, (short) 3681, (short) 3596,
    (short) 3514, (short) 3433, (short) 3354, (short) 3276, (short) 3201, (short) 3127,
        (short) 3054, (short) 2983, (short) 2914, (short) 2846, (short) 2779, (short) 2714,
    (short) 2651, (short) 2588, (short) 2527, (short) 2468, (short) 2409, (short) 2352,
        (short) 2296, (short) 2242, (short) 2188, (short) 2136, (short) 2084, (short) 2034,
    (short) 1985, (short) 1937, (short) 1890, (short) 1844, (short) 1799, (short) 1755,
        (short) 1712, (short) 1670, (short) 1628, (short) 1588, (short) 1548, (short) 1510,
    (short) 1472, (short) 1435, (short) 1399, (short) 1363, (short) 1328, (short) 1294,
        (short) 1261, (short) 1229, (short) 1197, (short) 1166, (short) 1135, (short) 1105,
    (short) 1076, (short) 1048, (short) 1020, (short) 992, (short) 966, (short) 939, (short) 914,
        (short) 889, (short) 864, (short) 840, (short) 817, (short) 794,
    (short) 771, (short) 749, (short) 727, (short) 706, (short) 686, (short) 665, (short) 646,
        (short) 626, (short) 607, (short) 589, (short) 571, (short) 553,
    (short) 536, (short) 519, (short) 502, (short) 486, (short) 470, (short) 454, (short) 439,
        (short) 424, (short) 410, (short) 395, (short) 381, (short) 368,
    (short) 354, (short) 341, (short) 328, (short) 316, (short) 304, (short) 292, (short) 280,
        (short) 268, (short) 257, (short) 246, (short) 235, (short) 225,
    (short) 214, (short) 204, (short) 194, (short) 185, (short) 175, (short) 166, (short) 157,
        (short) 148, (short) 139, (short) 131, (short) 123, (short) 114,
    (short) 106, (short) 99, (short) 91, (short) 84, (short) 76, (short) 69, (short) 62, (short) 55,
        (short) 49, (short) 42, (short) 36, (short) 29,
    (short) 23, (short) 17, (short) 11, (short) 6, (short) 0
  };

  public static final short[] decayTableSmall4 = {
    (short) 65535, (short) 64782, (short) 64037, (short) 63300, (short) 62571, (short) 61850,
        (short) 61137, (short) 60431, (short) 59733, (short) 59043, (short) 58360, (short) 57684,
    (short) 57016, (short) 56354, (short) 55700, (short) 55053, (short) 54413, (short) 53780,
        (short) 53153, (short) 52534, (short) 51921, (short) 51314, (short) 50715, (short) 50121,
    (short) 49534, (short) 48954, (short) 48379, (short) 47811, (short) 47249, (short) 46693,
        (short) 46143, (short) 45599, (short) 45061, (short) 44528, (short) 44001, (short) 43480,
    (short) 42965, (short) 42455, (short) 41951, (short) 41452, (short) 40958, (short) 40470,
        (short) 39987, (short) 39509, (short) 39036, (short) 38569, (short) 38106, (short) 37649,
    (short) 37196, (short) 36748, (short) 36305, (short) 35867, (short) 35434, (short) 35005,
        (short) 34581, (short) 34161, (short) 33746, (short) 33336, (short) 32930, (short) 32528,
    (short) 32130, (short) 31737, (short) 31348, (short) 30964, (short) 30583, (short) 30206,
        (short) 29834, (short) 29466, (short) 29101, (short) 28741, (short) 28384, (short) 28031,
    (short) 27682, (short) 27337, (short) 26995, (short) 26657, (short) 26323, (short) 25993,
        (short) 25666, (short) 25342, (short) 25022, (short) 24705, (short) 24392, (short) 24082,
    (short) 23776, (short) 23473, (short) 23173, (short) 22876, (short) 22583, (short) 22292,
        (short) 22005, (short) 21721, (short) 21440, (short) 21162, (short) 20887, (short) 20615,
    (short) 20346, (short) 20080, (short) 19816, (short) 19556, (short) 19298, (short) 19043,
        (short) 18791, (short) 18541, (short) 18295, (short) 18050, (short) 17809, (short) 17570,
    (short) 17334, (short) 17100, (short) 16869, (short) 16640, (short) 16414, (short) 16190,
        (short) 15968, (short) 15749, (short) 15532, (short) 15318, (short) 15106, (short) 14896,
    (short) 14689, (short) 14483, (short) 14280, (short) 14079, (short) 13881, (short) 13684,
        (short) 13490, (short) 13297, (short) 13107, (short) 12919, (short) 12732, (short) 12548,
    (short) 12366, (short) 12186, (short) 12007, (short) 11831, (short) 11657, (short) 11484,
        (short) 11313, (short) 11144, (short) 10977, (short) 10812, (short) 10648, (short) 10486,
    (short) 10326, (short) 10168, (short) 10012, (short) 9857, (short) 9703, (short) 9552,
        (short) 9402, (short) 9254, (short) 9107, (short) 8962, (short) 8818, (short) 8676,
    (short) 8535, (short) 8396, (short) 8259, (short) 8123, (short) 7988, (short) 7855,
        (short) 7724, (short) 7593, (short) 7464, (short) 7337, (short) 7211, (short) 7086,
    (short) 6963, (short) 6841, (short) 6720, (short) 6600, (short) 6482, (short) 6365,
        (short) 6250, (short) 6135, (short) 6022, (short) 5910, (short) 5800, (short) 5690,
    (short) 5582, (short) 5474, (short) 5368, (short) 5264, (short) 5160, (short) 5057,
        (short) 4956, (short) 4855, (short) 4756, (short) 4658, (short) 4560, (short) 4464,
    (short) 4369, (short) 4275, (short) 4182, (short) 4090, (short) 3998, (short) 3908,
        (short) 3819, (short) 3731, (short) 3644, (short) 3557, (short) 3472, (short) 3388,
    (short) 3304, (short) 3221, (short) 3140, (short) 3059, (short) 2979, (short) 2900,
        (short) 2821, (short) 2744, (short) 2667, (short) 2591, (short) 2516, (short) 2442,
    (short) 2369, (short) 2296, (short) 2224, (short) 2153, (short) 2083, (short) 2014,
        (short) 1945, (short) 1877, (short) 1810, (short) 1743, (short) 1677, (short) 1612,
    (short) 1548, (short) 1484, (short) 1421, (short) 1359, (short) 1297, (short) 1236,
        (short) 1175, (short) 1116, (short) 1057, (short) 998, (short) 940, (short) 883,
    (short) 827, (short) 771, (short) 715, (short) 660, (short) 606, (short) 553, (short) 500,
        (short) 447, (short) 395, (short) 344, (short) 293, (short) 243,
    (short) 193, (short) 144, (short) 96, (short) 48, (short) 0
  };

  public static final short[] sineWaveSmall = {
    0, 804, 1608, 2410, 3212, 4011, 4808, 5602, 6393, 7179, 7962, 8739, 9512, 10278, 11039, 11793,
    12539, 13279, 14010, 14732, 15446, 16151, 16846, 17530, 18204, 18868, 19519, 20159, 20787,
    21403, 22005, 22594, 23170, 23731, 24279, 24811, 25329, 25832, 26319, 26790, 27245, 27683,
    28105, 28510, 28898, 29268, 29621, 29956, 30273, 30571, 30852, 31113, 31356, 31580, 31785,
    31971, 32137, 32285, 32412, 32521, 32609, 32678, 32728, 32757, 32767, 32757, 32728, 32678,
    32609, 32521, 32412, 32285, 32137, 31971, 31785, 31580, 31356, 31113, 30852, 30571, 30273,
    29956, 29621, 29268, 28898, 28510, 28105, 27683, 27245, 26790, 26319, 25832, 25329, 24811,
    24279, 23731, 23170, 22594, 22005, 21403, 20787, 20159, 19519, 18868, 18204, 17530, 16846,
    16151, 15446, 14732, 14010, 13279, 12539, 11793, 11039, 10278, 9512, 8739, 7962, 7179, 6393,
    5602, 4808, 4011, 3212, 2410, 1608, 804, 0, -804, -1608, -2410, -3212, -4011, -4808, -5602,
    -6393, -7179, -7962, -8739, -9512, -10278, -11039, -11793, -12539, -13279, -14010, -14732,
    -15446, -16151, -16846, -17530, -18204, -18868, -19519, -20159, -20787, -21403, -22005, -22594,
    -23170, -23731, -24279, -24811, -25329, -25832, -26319, -26790, -27245, -27683, -28105, -28510,
    -28898, -29268, -29621, -29956, -30273, -30571, -30852, -31113, -31356, -31580, -31785, -31971,
    -32137, -32285, -32412, -32521, -32609, -32678, -32728, -32757, -32767, -32757, -32728, -32678,
    -32609, -32521, -32412, -32285, -32137, -31971, -31785, -31580, -31356, -31113, -30852, -30571,
    -30273, -29956, -29621, -29268, -28898, -28510, -28105, -27683, -27245, -26790, -26319, -25832,
    -25329, -24811, -24279, -23731, -23170, -22594, -22005, -21403, -20787, -20159, -19519, -18868,
    -18204, -17530, -16846, -16151, -15446, -14732, -14010, -13279, -12539, -11793, -11039, -10278,
    -9512, -8739, -7962, -7179, -6393, -5602, -4808, -4011, -3212, -2410, -1608, -804, 0
  };

  public static final short[] resonanceThresholdsForOversampling = {
    16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384,
    16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384,
    16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384,
    16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384, 16384,
    15500, 20735, 17000, 9000, 9000, 9000, 9000, 9000, 9000, 9000, 9000, 9000, 9000
  };

  public static final short[] resonanceLimitTable = {
    32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767,
    32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767,
    32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767,
    32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767, 32767,
    28415, 20000, 17000, 17000, 17000, 17000, 17000, 17000, 17000, 17000, 17000, 17000, 17000
  };

  public static final short[] expTableSmall = {
    (short) 32768, (short) 32857, (short) 32946, (short) 33035, (short) 33125, (short) 33215,
        (short) 33305, (short) 33395, (short) 33486, (short) 33576, (short) 33667, (short) 33759,
        (short) 33850, (short) 33942, (short) 34034, (short) 34126,
    (short) 34219, (short) 34312, (short) 34405, (short) 34498, (short) 34591, (short) 34685,
        (short) 34779, (short) 34874, (short) 34968, (short) 35063, (short) 35158, (short) 35253,
        (short) 35349, (short) 35445, (short) 35541, (short) 35637,
    (short) 35734, (short) 35831, (short) 35928, (short) 36025, (short) 36123, (short) 36221,
        (short) 36319, (short) 36417, (short) 36516, (short) 36615, (short) 36715, (short) 36814,
        (short) 36914, (short) 37014, (short) 37114, (short) 37215,
    (short) 37316, (short) 37417, (short) 37518, (short) 37620, (short) 37722, (short) 37824,
        (short) 37927, (short) 38030, (short) 38133, (short) 38236, (short) 38340, (short) 38444,
        (short) 38548, (short) 38653, (short) 38757, (short) 38863,
    (short) 38968, (short) 39074, (short) 39180, (short) 39286, (short) 39392, (short) 39499,
        (short) 39606, (short) 39714, (short) 39821, (short) 39929, (short) 40037, (short) 40146,
        (short) 40255, (short) 40364, (short) 40473, (short) 40583,
    (short) 40693, (short) 40804, (short) 40914, (short) 41025, (short) 41136, (short) 41248,
        (short) 41360, (short) 41472, (short) 41584, (short) 41697, (short) 41810, (short) 41923,
        (short) 42037, (short) 42151, (short) 42265, (short) 42380,
    (short) 42495, (short) 42610, (short) 42726, (short) 42841, (short) 42958, (short) 43074,
        (short) 43191, (short) 43308, (short) 43425, (short) 43543, (short) 43661, (short) 43780,
        (short) 43898, (short) 44017, (short) 44137, (short) 44256,
    (short) 44376, (short) 44497, (short) 44617, (short) 44738, (short) 44859, (short) 44981,
        (short) 45103, (short) 45225, (short) 45348, (short) 45471, (short) 45594, (short) 45718,
        (short) 45842, (short) 45966, (short) 46091, (short) 46216,
    (short) 46341, (short) 46467, (short) 46593, (short) 46719, (short) 46846, (short) 46973,
        (short) 47100, (short) 47228, (short) 47356, (short) 47484, (short) 47613, (short) 47742,
        (short) 47871, (short) 48001, (short) 48131, (short) 48262,
    (short) 48393, (short) 48524, (short) 48655, (short) 48787, (short) 48920, (short) 49052,
        (short) 49185, (short) 49319, (short) 49452, (short) 49586, (short) 49721, (short) 49856,
        (short) 49991, (short) 50126, (short) 50262, (short) 50399,
    (short) 50535, (short) 50672, (short) 50810, (short) 50947, (short) 51085, (short) 51224,
        (short) 51363, (short) 51502, (short) 51642, (short) 51782, (short) 51922, (short) 52063,
        (short) 52204, (short) 52346, (short) 52488, (short) 52630,
    (short) 52773, (short) 52916, (short) 53059, (short) 53203, (short) 53347, (short) 53492,
        (short) 53637, (short) 53782, (short) 53928, (short) 54074, (short) 54221, (short) 54368,
        (short) 54515, (short) 54663, (short) 54811, (short) 54960,
    (short) 55109, (short) 55258, (short) 55408, (short) 55558, (short) 55709, (short) 55860,
        (short) 56012, (short) 56163, (short) 56316, (short) 56468, (short) 56622, (short) 56775,
        (short) 56929, (short) 57083, (short) 57238, (short) 57393,
    (short) 57549, (short) 57705, (short) 57861, (short) 58018, (short) 58176, (short) 58333,
        (short) 58491, (short) 58650, (short) 58809, (short) 58968, (short) 59128, (short) 59289,
        (short) 59449, (short) 59611, (short) 59772, (short) 59934,
    (short) 60097, (short) 60260, (short) 60423, (short) 60587, (short) 60751, (short) 60916,
        (short) 61081, (short) 61247, (short) 61413, (short) 61579, (short) 61746, (short) 61914,
        (short) 62081, (short) 62250, (short) 62419, (short) 62588,
    (short) 62757, (short) 62928, (short) 63098, (short) 63269, (short) 63441, (short) 63613,
        (short) 63785, (short) 63958, (short) 64132, (short) 64306, (short) 64480, (short) 64655,
        (short) 64830, (short) 65006, (short) 65182, (short) 65359,
    (short) 65535
  };

  public static final short[] tanHSmall = {
    -32767, -32766, -32764, -32762, -32761, -32759, -32757, -32755, -32753, -32750, -32748, -32745,
    -32742, -32739, -32736, -32733, -32729, -32725, -32721, -32717, -32712, -32707, -32702, -32697,
    -32691, -32684, -32677, -32670, -32663, -32655, -32646, -32637, -32627, -32616, -32605, -32594,
    -32581, -32568, -32553, -32538, -32522, -32505, -32487, -32467, -32447, -32425, -32401, -32377,
    -32350, -32322, -32292, -32260, -32227, -32191, -32152, -32112, -32069, -32023, -31974, -31922,
    -31867, -31808, -31746, -31680, -31610, -31535, -31456, -31372, -31282, -31187, -31087, -30980,
    -30867, -30747, -30619, -30484, -30341, -30190, -30029, -29859, -29679, -29488, -29287, -29074,
    -28849, -28611, -28359, -28094, -27814, -27519, -27208, -26881, -26536, -26174, -25792, -25392,
    -24972, -24531, -24069, -23586, -23080, -22552, -22000, -21425, -20826, -20202, -19555, -18882,
    -18185, -17463, -16717, -15946, -15152, -14335, -13495, -12633, -11750, -10847, -9926, -8986,
    -8031, -7060, -6077, -5082, -4077, -3065, -2047, -1024, 0, 1024, 2047, 3065, 4077, 5082, 6077,
    7060, 8031, 8986, 9926, 10847, 11750, 12633, 13495, 14335, 15152, 15946, 16717, 17463, 18185,
    18882, 19555, 20202, 20826, 21425, 22000, 22552, 23080, 23586, 24069, 24531, 24972, 25392,
    25792, 26174, 26536, 26881, 27208, 27519, 27814, 28094, 28359, 28611, 28849, 29074, 29287,
    29488, 29679, 29859, 30029, 30190, 30341, 30484, 30619, 30747, 30867, 30980, 31087, 31187,
    31282, 31372, 31456, 31535, 31610, 31680, 31746, 31808, 31867, 31922, 31974, 32023, 32069,
    32112, 32152, 32191, 32227, 32260, 32292, 32322, 32350, 32377, 32401, 32425, 32447, 32467,
    32487, 32505, 32522, 32538, 32553, 32568, 32581, 32594, 32605, 32616, 32627, 32637, 32646,
    32655, 32663, 32670, 32677, 32684, 32691, 32697, 32702, 32707, 32712, 32717, 32721, 32725,
    32729, 32733, 32736, 32739, 32742, 32745, 32748, 32750, 32753, 32755, 32757, 32759, 32761,
    32762, 32764, 32766, 32767
  };

  // FM Specific LUTs
  public static final int SIN_N_SAMPLES = 1024;
  public static final int EXP2_N_SAMPLES = 1024;
  public static final int TANH_N_SAMPLES = 1024;

  public static final int[] sinTab = new int[SIN_N_SAMPLES << 1];
  public static final int[] exp2Tab = new int[EXP2_N_SAMPLES << 1];
  public static final int[] tanhTab = new int[TANH_N_SAMPLES << 1];

  static {
    sinInit();
    exp2Init();
    tanhInit();
  }

  private static void sinInit() {
    double dphase = 2 * Math.PI / SIN_N_SAMPLES;
    int c = (int) Math.floor(Math.cos(dphase) * (1 << 30) + 0.5);
    int s = (int) Math.floor(Math.sin(dphase) * (1 << 30) + 0.5);
    int u = 1 << 30;
    int v = 0;
    for (int i = 0; i < SIN_N_SAMPLES / 2; i++) {
      sinTab[(i << 1) + 1] = (v + 32) >> 6;
      sinTab[((i + SIN_N_SAMPLES / 2) << 1) + 1] = -((v + 32) >> 6);
      int t = (int) (((long) u * s + (long) v * c + (1 << 29)) >> 30);
      u = (int) (((long) u * c - (long) v * s + (1 << 29)) >> 30);
      v = t;
    }
    for (int i = 0; i < SIN_N_SAMPLES - 1; i++) {
      sinTab[i << 1] = sinTab[(i << 1) + 3] - sinTab[(i << 1) + 1];
    }
    sinTab[(SIN_N_SAMPLES << 1) - 2] = -sinTab[(SIN_N_SAMPLES << 1) - 1];
  }

  private static void exp2Init() {
    double inc = Math.pow(2.0, 1.0 / EXP2_N_SAMPLES);
    double y = 1 << 30;
    for (int i = 0; i < EXP2_N_SAMPLES; i++) {
      exp2Tab[(i << 1) + 1] = (int) Math.floor(y + 0.5);
      y *= inc;
    }
    for (int i = 0; i < EXP2_N_SAMPLES - 1; i++) {
      exp2Tab[i << 1] = exp2Tab[(i << 1) + 3] - exp2Tab[(i << 1) + 1];
    }
    exp2Tab[(EXP2_N_SAMPLES << 1) - 2] = (1 << 31) - exp2Tab[(EXP2_N_SAMPLES << 1) - 1];
  }

  private static void tanhInit() {
    double step = 4.0 / TANH_N_SAMPLES;
    double y = 0;
    for (int i = 0; i < TANH_N_SAMPLES; i++) {
      tanhTab[(i << 1) + 1] = (int) ((1 << 24) * y + 0.5);
      double k1 = 1 - y * y;
      double k2 = 1 - (y + 0.5 * step * k1) * (y + 0.5 * step * k1);
      double k3 = 1 - (y + 0.5 * step * k2) * (y + 0.5 * step * k2);
      double k4 = 1 - (y + step * k3) * (y + step * k3);
      double dy = (step / 6) * (k1 + k4 + 2 * (k2 + k3));
      y += dy;
    }
    for (int i = 0; i < TANH_N_SAMPLES - 1; i++) {
      tanhTab[i << 1] = tanhTab[(i << 1) + 3] - tanhTab[(i << 1) + 1];
    }
    int lasty = (int) ((1 << 24) * y + 0.5);
    tanhTab[(TANH_N_SAMPLES << 1) - 2] = lasty - tanhTab[(TANH_N_SAMPLES << 1) - 1];
  }

  public static int sinLookup(int phase) {
    int SHIFT = 24 - 10; // SIN_LG_N_SAMPLES = 10
    int lowbits = phase & ((1 << SHIFT) - 1);
    int phase_int = (phase >> (SHIFT - 1)) & ((SIN_N_SAMPLES - 1) << 1);
    int dy = sinTab[phase_int];
    int y0 = sinTab[phase_int + 1];
    return y0 + (int) (((long) dy * lowbits) >> SHIFT);
  }

  public static int exp2Lookup(int x) {
    int SHIFT = 24 - 10;
    int x_int = (x >> (SHIFT - 1)) & ((EXP2_N_SAMPLES - 1) << 1);
    int lowbits = x & ((1 << SHIFT) - 1);
    int dy = exp2Tab[x_int];
    int y0 = exp2Tab[x_int + 1];
    int y = y0 + (int) (((long) dy * lowbits) >> SHIFT);
    return y >> (14 - (x >> 24));
  }

  public static int tanhLookup(int x) {
    int SHIFT = 24 - 10;
    int x_abs = Math.abs(x);
    int x_int = (x_abs >> (SHIFT - 1)) & ((TANH_N_SAMPLES - 1) << 1);
    int lowbits = x_abs & ((1 << SHIFT) - 1);
    int dy = tanhTab[x_int];
    int y0 = tanhTab[x_int + 1];
    int y = y0 + (int) (((long) dy * lowbits) >> SHIFT);
    return x < 0 ? -y : y;
  }

  /**
   * Port of the firmware {@code releaseRateTable64[65]}: envelope decay/release per-sample
   * increments, indexed by {@link FirmwareUtils#lookupReleaseRate}.
   */
  public static final int[] releaseRateTable64 = {
    1959518848, 240577040, 126456408, 84972672, 63518640, 50408868, 41567836, 35202512,
    30400724, 26649400, 23637820, 21166812, 19102814, 17352918, 15850494, 14546511,
    13404089, 12394955, 11497068, 10692993, 9968758, 9313033, 8716538, 8171592,
    7671792, 7211749, 6786901, 6393359, 6027784, 5687299, 5369406, 5071928,
    4792960, 4530827, 4284050, 4051316, 3831462, 3623446, 3426337, 3239300,
    3061582, 2892503, 2731448, 2577859, 2431229, 2291096, 2157036, 2028664,
    1905625, 1787593, 1674270, 1565377, 1460662, 1359888, 1262836, 1169305,
    1079105, 992062, 908014, 826807, 748301, 672363, 598870, 527703,
    458757,
  };
}
