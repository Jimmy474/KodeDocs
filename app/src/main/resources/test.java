public class test {
    // #region main
    public static void method(int someArg){
        int temp = someArg;
        // #region temp
        temp++; // [!code ++]
        temp++; // [!code --]
        // #endregion temp
    }
    // #endregion main
}