package dev.linguaflow

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.time.OffsetDateTime

internal object RuntimeContractJson {
  val gson: Gson = GsonBuilder()
    .registerTypeAdapter(OffsetDateTime::class.java, OffsetDateTimeAdapter())
    .create()
}

private class OffsetDateTimeAdapter : TypeAdapter<OffsetDateTime>() {
  override fun write(output: JsonWriter, value: OffsetDateTime?) {
    if (value == null) output.nullValue() else output.value(value.toString())
  }

  override fun read(input: JsonReader): OffsetDateTime = OffsetDateTime.parse(input.nextString())
}
