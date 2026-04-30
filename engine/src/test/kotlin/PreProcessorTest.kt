import PreProcessor.Companion.REGION_NAME_REGEX
import kotlin.test.Test
import kotlin.test.assertEquals

class PreProcessorTest {

    private val preProcessor = PreProcessor()

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
        val result = preProcessor.processKodeDocs(input, listOf("main"), emptyList())
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
        val result = preProcessor.processKodeDocs(input, emptyList(), listOf("main"))
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
        
        // Include main, should include everything inside it (except region markers)
        val expectedMain = """
            public static void method(int someArg){
                int temp = someArg;
                temp++;
            }
        """.trimIndent()
        assertEquals(expectedMain, preProcessor.processKodeDocs(input, listOf("main"), emptyList()))

        // Include temp, should only include what's inside temp
        val expectedTemp = "temp++;"
        assertEquals(expectedTemp, preProcessor.processKodeDocs(input, listOf("temp"), emptyList()))
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

        // Include main, exclude temp
        val expected = """
            public static void method(int someArg){
                int temp = someArg;
            }
        """.trimIndent()
        val result = preProcessor.processKodeDocs(input, listOf("main"), listOf("temp"))
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
        
        // If we include A but exclude B, B should be excluded even if it's inside A.
        val result = preProcessor.processKodeDocs(input, listOf("A"), listOf("B"))
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
        val result = preProcessor.processKodeDocs(input, listOf("reg1", "reg2"), emptyList())
        assertEquals(expected, result)
    }

    @Test
    fun `test overlapping regions`() {
        // Note: The current implementation might handle this weirdly because it just uses counters.
        val input = """
            // #region A
            line A1
            // #region B
            line AB
            // #endregion A
            line B2
            // #endregion B
        """.trimIndent()

        // If we include A:
        // line A1 (skipping=0, including=1) -> OK
        // line AB (skipping=0, including=2) -> OK
        // // #endregion A (including becomes 1)
        // line B2 (skipping=0, including=1) -> OK? Wait, A ended. 
        // But B is still "including". 
        // The requirement says "if its not empty only included regions are returned".
        // If A is included, should B2 be included? B2 is in B, but not in A.
        
        // Actually, looking at the code:
        // if(name in include) including++
        // ...
        // if(!isRegionMarker && skipping == 0 && (include.isNotEmpty() && including > 0))
        
        // If I include ONLY A:
        // line A1: including=1 -> OK
        // line AB: including=2 (since B is not in include, it doesn't increment including)
        // Wait:
        // else if(name in include) including++
        
        // If B is NOT in include, `including` does NOT increment when entering B.
        // So line AB is included because including=1 (from A).
        // Then // #endregion A -> including becomes 0.
        // Then line B2: including=0 -> NOT OK.
        
        // This seems correct for overlapping if we think of them as sets.
        
        val expectedA = "line A1\nline AB"
        assertEquals(expectedA, preProcessor.processKodeDocs(input, listOf("A"), emptyList()))
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
        assertEquals(expected, preProcessor.processKodeDocs(input, listOf("A"), emptyList()))
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
        
        // Exclude sub, should keep 1 and 3 if include is empty
        val expectedExcludeSub = "line 1\nline 3"
        assertEquals(expectedExcludeSub, preProcessor.processKodeDocs(input, emptyList(), listOf("sub")))

        // Include main, exclude sub
        val expectedIncludeMainExcludeSub = "line 1\nline 3"
        assertEquals(expectedIncludeMainExcludeSub, preProcessor.processKodeDocs(input, listOf("main"), listOf("sub")))
    }

    @Test
    fun `test malformed region names throw exception`() {
        val input = """
            // #region A B
            line 1
            // #endregion A B
        """.trimIndent()
        
        val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
            preProcessor.processKodeDocs(input, emptyList(), emptyList(), "test.java")
        }
        assertEquals("Invalid region name 'A B' in test.java:1. Valid Region name Regex: $REGION_NAME_REGEX", exception.message)
    }

    @Test
    fun `test missing region name throws exception`() {
        val input = """
            // #region 
            line 1
        """.trimIndent()
        
        val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
            preProcessor.processKodeDocs(input, emptyList(), emptyList(), "test.java")
        }
        assertEquals("Region name is missing in test.java:1", exception.message)
    }

    @Test
    fun `test invalid region name starting with digit throws exception`() {
        val input = """
            // #region 123invalid
            content
        """.trimIndent()
        
        val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
            preProcessor.processKodeDocs(input, emptyList(), emptyList(), "test.java")
        }
        assertEquals("Invalid region name '123invalid' in test.java:1. Valid Region name Regex: $REGION_NAME_REGEX", exception.message)
    }

    @Test
    fun `test region with no end`() {
        val input = """
            // #region A
            line 1
        """.trimIndent()
        // including will stay 1 until the end
        val expected = "line 1"
        assertEquals(expected, preProcessor.processKodeDocs(input, listOf("A"), emptyList()))
    }

    @Test
    fun `test comments on same line`() {
        val input = """
            // #region A
            line 1 // comment
            // #endregion A
        """.trimIndent()
        val expected = "line 1 // comment"
        assertEquals(expected, preProcessor.processKodeDocs(input, listOf("A"), emptyList()))
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
        val expected = "    int x = 1;"
        // Note: the current processKodeDocs does NOT trim the output line if it matches.
        // It does output += "$line\n"
        // And then trimIndent() on the WHOLE output at the end.
        // If the only line is "    int x = 1;", trimIndent() will remove the common indent (4 spaces).
        // Let's see what happens.
        val result = preProcessor.processKodeDocs(input, listOf("A"), emptyList())
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
        val result = preProcessor.processKodeDocs(input, listOf("A"), emptyList())
        // Both lines have 4 spaces indent. trimIndent() will remove them.
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
        val result = preProcessor.processKodeDocs(input, listOf("_validName", "validName2"), emptyList())
        assertEquals("content1\ncontent2", result)
    }

    @Test
    fun `test region marker with multiple spaces`() {
        val input = """
            //    #region    A   
            content
            //    #endregion    A   
        """.trimIndent()
        
        val result = preProcessor.processKodeDocs(input, listOf("A"), emptyList(), "test.java")
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
        val result = preProcessor.processKodeDocs(input, listOf("A"), emptyList(), "test.java")
        assertEquals(expected, result)
    }
}
