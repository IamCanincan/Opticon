set -e
cd /d/Github\ Repositories/Opticon/_harness
. ./jdk.sh
M=/c/Users/20653/.gradle/caches/modules-2/files-2.1
KC=$(ls $M/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.20/*/kotlin-compiler-embeddable-2.4.20.jar)
SL=$(ls $M/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/kotlin-stdlib-2.4.20.jar)
CO=$(ls $M/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/*/*.jar)
AN=$(ls $M/org.jetbrains/annotations/*/*/annotations-*.jar | tail -1)
win() { cygpath -w "$1"; }
KCP="$(win "$KC");$(win "$SL");$(win "$CO");$(win "$AN")"
rm -rf sv_old
"$JAVA" -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -nowarn \
  -classpath "$(win "$SL");$(win var_old);$(win stubs_out)" -Xfriend-paths="$(win var_old)" \
  -d sv_old SizeVarKt.kt > /dev/null
"$JAVA" -Dstdout.encoding=UTF-8 -cp "$(win sv_old);$(win var_old);$(win stubs_out);$(win "$SL")" \
  harness.SizeVarKtKt rec_model --via=256,320 --all > _sv_old.txt
"$JAVA" -Dstdout.encoding=UTF-8 -cp "$(win out);$(win stubs_out);$(win "$SL")" \
  harness.SizeVarKtKt rec_model --via=256,320 --all > _sv_new.txt
echo OLD:; grep '^SV ' _sv_old.txt
echo NEW:; grep '^SV ' _sv_new.txt
