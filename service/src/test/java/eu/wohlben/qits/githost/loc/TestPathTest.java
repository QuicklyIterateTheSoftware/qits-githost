package eu.wohlben.qits.githost.loc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TestPathTest {

  @Test
  public void aTestDirectorySegmentClaimsEverythingBeneathIt() {
    assertTrue(TestPath.isTest("src/test/java/AppHelper.java"));
    assertTrue(TestPath.isTest("e2e/tests/flow.ts"));
    assertTrue(TestPath.isTest("web/__tests__/util.js"));
    assertTrue(TestPath.isTest("spec/fixtures/data.json"));
  }

  @Test
  public void aSpecOrTestFilenameSuffixIsTestCodeWhereverItSits() {
    assertTrue(TestPath.isTest("src/app/code/code-page.spec.ts"));
    assertTrue(TestPath.isTest("src/app/util.test.tsx"));
    assertTrue(TestPath.isTest("scripts/check.test.js"));
  }

  @Test
  public void aJvmTestClassSuffixIsTestCode() {
    assertTrue(TestPath.isTest("src/other/AppTest.java"));
    assertTrue(TestPath.isTest("src/other/AppTests.java"));
    assertTrue(TestPath.isTest("src/other/AppIT.java"));
    assertTrue(TestPath.isTest("src/other/AppTest.kt"));
  }

  @Test
  public void theJvmSuffixIsCaseSensitiveSoEditAndCommitStayMainCode() {
    assertFalse(TestPath.isTest("src/main/java/edit.java"));
    assertFalse(TestPath.isTest("src/main/java/Commit.java"));
    assertFalse(TestPath.isTest("src/main/java/Contest.java"));
  }

  @Test
  public void everythingElseIsMainCode() {
    assertFalse(TestPath.isTest("src/main/java/App.java"));
    assertFalse(TestPath.isTest("web/thing.ts"));
    assertFalse(TestPath.isTest("README.md"));
    // the segment rule reads directories only; a file spelled like one is judged by its basename
    assertFalse(TestPath.isTest("src/test"));
  }
}
