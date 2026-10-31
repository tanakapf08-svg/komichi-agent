#!/system/bin/sh
# HOST TEST HARNESS ONLY. Run on a disposable rootable Android emulator after
# pushing build/root-script-fixtures to /data/local/tmp/komichi-root-fixture.
# Never target a real app's data. No user data is read by this test.
set -eu
[ "$(id -u)" = 0 ] || exit 70
[ "$(getprop ro.kernel.qemu)" = 1 ] || exit 71
base=/data/local/tmp/komichi-root-fixture
src=/data/user/0/dev.komichi.rootfixture
dest=/storage/emulated/0/Download/komichi-root-fixture
[ -d "$base" ] && [ ! -e "$src" ] && [ ! -e "$dest" ] || exit 72
mkdir -p "$src/files" "$dest"
printf 'synthetic-only-private-data\n' > "$src/files/note.txt"
printf 'hidden-fixture\n' > "$src/.hidden"
printf 'quoted-fixture\n' > "$src/files/quote' \$(id) §__JOB__"
printf 'outside-fixture\n' > "$base/outside.txt"

run() { cp "$base/$1.permit" "$base/permit"; sh "$base/$1.sh" > "$base/$1.stdout" 2> "$base/$1.stderr"; }
reject() {
    name="$1"; code="$2"
    if run "$name"; then printf 'FAIL %s unexpectedly succeeded\n' "$name"; exit 73; fi
    grep -q "KOMICHI_ERROR $code" "$base/$name.stderr"
    printf 'PASS %s rejects %s\n' "$name" "$code"
}
run list
grep -q KOMICHI_OK "$base/list.stdout"
grep -q .hidden "$base/list.stdout"
printf 'PASS list includes hidden entries\n'
run directory
cmp "$src/files/note.txt" "$dest/directory/files/note.txt"
cmp "$src/.hidden" "$dest/directory/.hidden"
cmp "$src/files/quote' \$(id) §__JOB__" "$dest/directory/files/quote' \$(id) §__JOB__"
[ "$(find "$dest/directory" -name '*.incomplete' | wc -l)" -eq 0 ]
printf 'PASS directory copy bytes, hidden/quoted names and completion\n'
reject directory DESTINATION_EXISTS
ln -s "$base/outside.txt" "$src/escape"
reject symlink-source SOURCE_SCOPE
mkdir "$base/storage"
ln -s "$base" "$base/storage/link"
reject symlink-destination DESTINATION_SYMLINK
run tar
tar -tf "$dest/backup.tar" | grep -q './escape'
mkdir "$base/extracted"
tar -xf "$dest/backup.tar" -C "$base/extracted"
[ -L "$base/extracted/escape" ]
cmp "$src/files/note.txt" "$base/extracted/files/note.txt"
printf 'PASS tar copy bytes and preserves symlink without following it\n'
rm "$base/permit"
if sh "$base/expired.sh" > "$base/expired.stdout" 2> "$base/expired.stderr"; then exit 74; fi
grep -q 'KOMICHI_ERROR CANCELLED' "$base/expired.stderr"
[ ! -e "$dest/expired" ]
printf 'PASS expired execution lease creates no copy\n'
# Remove the source symlink before a raw copy to a FUSE destination.
rm "$src/escape"
run odd-name
cmp "$src/files/note.txt" "$dest/quote' \$(id) §__JOB__/files/note.txt"
printf 'PASS shell metacharacters are literal destination names\n'
run single-file
cmp "$src/files/note.txt" "$dest/single-file"
printf 'PASS individual file copy bytes\n'
run single-tar
mkdir "$base/extracted-single"
tar -xf "$dest/single.tar" -C "$base/extracted-single"
cmp "$src/files/note.txt" "$base/extracted-single/note.txt"
printf 'PASS individual file tar copy bytes\n'
printf 'PASS source bytes remain unchanged: '
sha256sum "$src/files/note.txt"
printf 'ROOT_FILE_INTEGRATION_OK\n'
