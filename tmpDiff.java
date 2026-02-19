import app.DiffEngine;
public class tmpDiff {
  public static void main(String[] args) {
    String left = "Lima\nMike\nNovember\n";
    String right = "Lima\nNovember\n";
    DiffEngine.Result base = DiffEngine.diffLines(left, right);
    System.out.println("Base:");
    base.hunks.forEach(System.out::println);
    DiffEngine.Result refined = DiffEngine.refineChanges(base, left, right);
    System.out.println("Refined:");
    refined.hunks.forEach(System.out::println);
  }
}
