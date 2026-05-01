public class test {
    // #region main
    public static void method(int someArg){
        int temp = someArg; // [!code ++]
        // #region temp
        temp++; // [!code --]
        temp++; // [!code word:temp]
        temp++;
        temp++; // [!code info]
        temp++; // [!code error]
        temp++; // [!code warning]
        // #endregion temp
        //Random Comments
    }
    // #endregion main
}