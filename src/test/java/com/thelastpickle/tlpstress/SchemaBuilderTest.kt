package com.thelastpickle.tlpstress

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class SchemaBuilderTest {

    lateinit var createTable : SchemaBuilder

    @BeforeEach
    fun setUp() {
        val statement = """CREATE TABLE test (
                                | id int primary key,
                                | name text
                                | ) """.trimMargin()

        createTable = SchemaBuilder.create(statement)
    }


    @Test
    fun compactionTest() {
        val result = createTable.withCompaction("{ 'class': 'LeveledCompactionStrategy', 'sstable_size_in_mb': 100}").build()
        assertThat(result).contains("sstable_size_in_mb': 100")
        assertThat(result).doesNotContain("compression")
    }

    @Test
    fun compressionTest() {
        val c = "{'enabled':false}"
        val result = createTable.withCompression(c).build()
        assertThat(result).contains(c)
    }
}