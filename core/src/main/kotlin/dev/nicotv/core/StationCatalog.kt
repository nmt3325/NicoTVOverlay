package dev.nicotv.core
/** Official portal mapping checked 2026-09-08. These are NOT remote key numbers. */
object StationCatalog {
 val stations = listOf(
  Station("jk1", "NHK総合", "ch2646436", listOf("NHK総合", "NHK G", "NHKG")),
  Station("jk2", "NHK Eテレ", "ch2646437", listOf("NHK Eテレ", "Eテレ", "NHK教育")),
  Station("jk4", "日本テレビ", "ch2646438", listOf("日本テレビ", "日テレ", "NTV")),
  Station("jk5", "テレビ朝日", "ch2646439", listOf("テレビ朝日", "テレ朝", "EX")),
  Station("jk6", "TBS", "ch2646440", listOf("TBS", "TBSテレビ")),
  Station("jk7", "テレビ東京", "ch2646441", listOf("テレビ東京", "テレ東", "TX")),
  Station("jk8", "フジテレビ", "ch2646442", listOf("フジテレビ", "CX")),
  Station("jk9", "TOKYO MX", "ch2646485", listOf("TOKYO MX", "TOKYOMX", "東京MX", "MXテレビ")),
  Station("jk101", "NHK BS", "ch2647992", listOf("NHK BS", "NHKBS", "NHK BS1")),
  Station("jk211", "BS11", "ch2646846", listOf("BS11", "BSイレブン", "日本BS放送"))
 )
 fun find(id: String?): Station? = stations.firstOrNull { it.id == id }
 /** ISDB service_id → 実況ch。録画再生中にテレビ内部ログのサービスIDから放送局を決めるため。 */
 private val serviceIds = mapOf(
  1024 to "jk1", 1025 to "jk1", 1026 to "jk1",
  1032 to "jk2", 1033 to "jk2", 1034 to "jk2",
  1040 to "jk4", 1041 to "jk4", 1042 to "jk4",
  1048 to "jk6", 1049 to "jk6", 1050 to "jk6",
  1056 to "jk8", 1057 to "jk8", 1058 to "jk8",
  1064 to "jk5", 1065 to "jk5", 1066 to "jk5",
  1072 to "jk7", 1073 to "jk7", 1074 to "jk7",
  23608 to "jk9", 23609 to "jk9", 23610 to "jk9",
  101 to "jk101", 102 to "jk101", 103 to "jk101",
  211 to "jk211"
 )
 fun findByServiceId(serviceId: Int): Station? = find(serviceIds[serviceId])
}
