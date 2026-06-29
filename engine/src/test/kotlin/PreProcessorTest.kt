
import PreProcessor.REGION_NAME_REGEX
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PreProcessorTest {

    private lateinit var tempFile: File

    @BeforeTest
    fun setup() {
        tempFile = Files.createTempFile("preprocessor_test", ".java").toFile()
    }

    @AfterTest
    fun teardown() {
        tempFile.delete()
    }

    private fun process(input: String, include: List<String> = emptyList(), exclude: List<String> = emptyList(), lineStrings: List<String> = emptyList()): String {
        tempFile.writeText(input)
        return PreProcessor.processKodeDocs(tempFile, include, exclude, lineStrings)
    }

    @Test
    fun `test basic include`() {
        val input = """
            public class test {
                // #region main
                public static void main() {}
                // #endregion main
            }
        """.trimIndent()
        val expected = "public static void main() {}"
        val result = process(input, listOf("main"), emptyList(), emptyList())
        assertEquals(expected, result)
    }

    @Test
    fun `test basic exclude`() {
        val input = """
            public class test {
                // #region main
                public static void main() {}
                // #endregion main
            }
        """.trimIndent()
        val expected = "public class test {\n}"
        val result = process(input, emptyList(), listOf("main"), emptyList())
        assertEquals(expected, result)
    }

    @Test
    fun `test nested include`() {
        val input = """
            public class test {
                // #region main
                public static void method(int someArg){
                    int temp = someArg;
                    // #region temp
                    temp++;
                    // #endregion temp
                }
                // #endregion main
            }
        """.trimIndent()

        val expectedMain = """
            public static void method(int someArg){
                int temp = someArg;
                temp++;
            }
        """.trimIndent()
        assertEquals(expectedMain, process(input, listOf("main"), emptyList(), emptyList()))

        val expectedTemp = "temp++;"
        assertEquals(expectedTemp, process(input, listOf("temp"), emptyList(), emptyList()))
    }

    @Test
    fun `test nested include and exclude`() {
        val input = """
            public class test {
                // #region main
                public static void method(int someArg){
                    int temp = someArg;
                    // #region temp
                    temp++;
                    // #endregion temp
                }
                // #endregion main
            }
        """.trimIndent()

        val expected = """
            public static void method(int someArg){
                int temp = someArg;
            }
        """.trimIndent()
        val result = process(input, listOf("main"), listOf("temp"), emptyList())
        assertEquals(expected, result)
    }

    @Test
    fun `test exclusion priority over inclusion`() {
        val input = """
            // #region A
            // #region B
            line inside A and B
            // #endregion B
            // #endregion A
        """.trimIndent()
        
        val result = process(input, listOf("A"), listOf("B"), emptyList())
        assertEquals("", result.trim())
    }

    @Test
    fun `test multiple includes`() {
        val input = """
            // #region reg1
            line1
            // #endregion reg1
            // #region reg2
            line2
            // #endregion reg2
            line3
        """.trimIndent()
        val expected = "line1\nline2"
        val result = process(input, listOf("reg1", "reg2"), emptyList(), emptyList())
        assertEquals(expected, result)
    }

    @Test
    fun `test overlapping regions`() {
        val input = """
            // #region A
            line A1
            // #region B
            line AB
            // #endregion A
            line B2
            // #endregion B
        """.trimIndent()
        
        val expectedA = "line A1\nline AB"
        assertEquals(expectedA, process(input, listOf("A"), emptyList(), emptyList()))
    }
    
    @Test
    fun `test same name regions`() {
         val input = """
            // #region A
            part 1
            // #endregion A
            middleware
            // #region A
            part 2
            // #endregion A
        """.trimIndent()
        val expected = "part 1\npart 2"
        assertEquals(expected, process(input, listOf("A"), emptyList(), emptyList()))
    }

    @Test
    fun `test nested exclude`() {
        val input = """
            // #region main
            line 1
            // #region sub
            line 2
            // #endregion sub
            line 3
            // #endregion main
        """.trimIndent()
        
        val expectedExcludeSub = "line 1\nline 3"
        assertEquals(expectedExcludeSub, process(input, emptyList(), listOf("sub"), emptyList()))

        val expectedIncludeMainExcludeSub = "line 1\nline 3"
        assertEquals(expectedIncludeMainExcludeSub, process(input, listOf("main"), listOf("sub"), emptyList()))
    }

    @Test
    fun `test malformed region names throw exception`() {
        val input = """
            // #region A B
            line 1
            // #endregion A B
        """.trimIndent()
        
        val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
            process(input, emptyList(), emptyList(), emptyList())
        }
        assertEquals("Invalid region name 'A B' in ${tempFile.name}:1. Valid Region name Regex: $REGION_NAME_REGEX", exception.message)
    }

    @Test
    fun `test missing region name throws exception`() {
        val input = """
            // #region 
            line 1
        """.trimIndent()
        
        val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
            process(input, emptyList(), emptyList(), emptyList())
        }
        assertEquals("Region name is missing in ${tempFile.name}:1", exception.message)
    }

    @Test
    fun `test invalid region name starting with digit throws exception`() {
        val input = """
            // #region 123invalid
            content
        """.trimIndent()
        
        val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
            process(input, emptyList(), emptyList(), emptyList())
        }
        assertEquals("Invalid region name '123invalid' in ${tempFile.name}:1. Valid Region name Regex: $REGION_NAME_REGEX", exception.message)
    }

    @Test
    fun `test region with no end`() {
        val input = """
            // #region A
            line 1
        """.trimIndent()
        val expected = "line 1"
        assertEquals(expected, process(input, listOf("A"), emptyList(), emptyList()))
    }

    @Test
    fun `test comments on same line`() {
        val input = """
            // #region A
            line 1 // comment
            // #endregion A
        """.trimIndent()
        val expected = "line 1 // comment"
        assertEquals(expected, process(input, listOf("A"), emptyList(), emptyList()))
    }

    @Test
    fun `test indented region markers`() {
        val input = """
            public class Test {
                // #region A
                int x = 1;
                // #endregion A
            }
        """.trimIndent()
        val result = process(input, listOf("A"), emptyList(), emptyList())
        assertEquals("int x = 1;", result)
    }

    @Test
    fun `test indented region markers with multiple lines`() {
        val input = """
            public class Test {
                // #region A
                int x = 1;
                int y = 2;
                // #endregion A
            }
        """.trimIndent()
        val result = process(input, listOf("A"), emptyList(), emptyList())
        assertEquals("int x = 1;\nint y = 2;", result)
    }

    @Test
    fun `test valid region names`() {
        val input = """
            // #region _validName
            content1
            // #endregion _validName
            // #region validName2
            content2
            // #endregion validName2
        """.trimIndent()
        val result = process(input, listOf("_validName", "validName2"), emptyList(), emptyList())
        assertEquals("content1\ncontent2", result)
    }

    @Test
    fun `test region marker with multiple spaces`() {
        val input = """
            //    #region    A   
            content
            //    #endregion    A   
        """.trimIndent()
        
        val result = process(input, listOf("A"), emptyList(), emptyList())
        assertEquals("content", result)
    }

    @Test
    fun `test marker without space after slashes`() {
        val input = """
            //#region A
            content
            //#endregion A
        """.trimIndent()
        val expected = "content"
        val result = process(input, listOf("A"), emptyList(), emptyList())
        assertEquals(expected, result)
    }
}
