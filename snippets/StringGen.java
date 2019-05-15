public class StringGen {
    public static void main(String ... args) {
        System.out.println("public class Strings { static byte b = 0; static boolean z = false; static char c = 'a'; static short s = 0; static int i = 0; static long j = 0L; static float f = 0.0f; static double d = 0.0; static String S = Strings.class.getName(); static Object o = new Object(); public static void main(String ... args) { String con; ");
        String[] types = {"b", "z", "c", "s", "i", "j", "f", "d", "S", "o"};
        int val = 0;
        for (int a0 = 0; a0 < types.length; a0++) {
         for (int a1 = 0; a1 < types.length; a1++) {
          for (int a2 = 0; a2 < types.length; a2++) {
           for (int a3 = 0; a3 < types.length; a3++) {
            System.out.println("Foo" + val++ + ".foo();");
           }
          }
         }
        }
        System.out.println("}");
        val = 0;
        for (int a0 = 0; a0 < types.length; a0++) {
         for (int a1 = 0; a1 < types.length; a1++) {
          for (int a2 = 0; a2 < types.length; a2++) {
           for (int a3 = 0; a3 < types.length; a3++) {
            System.out.println("private static class Foo" + val++ + " { static void foo() { String con;");
            for (int x = 0; x < 32; x++) {
             String c0 = ((x&0x1) >0) ? "\"f\"" : "\"\"";
             String c1 = ((x&0x2) >0) ? "+\"f\"" : "";
             String c2 = ((x&0x4) >0) ? "+\"f\"" : "";
             String c3 = ((x&0x8) >0) ? "+\"f\"" : "";
             String c4 = ((x&0x10)>0) ? "+\"f\"" : "";
             System.out.println("con = " + c0 + "+" + types[a0] + c1 + "+" + types[a1] + c2 + "+" + types[a2] + c3 + "+" + types[a3] + c4 + ";");
            }
            System.out.println("}}");
           }
          }
         }
        }
        System.out.println("}");
    }
}
