#!/usr/bin/env bash
# Builds a signed pacman repository from the CLI's distribution tar.
# The signing key must already be in the gpg keyring.
#
# Usage: build-repo.sh <version> <path to blinds.tar> <output directory>
set -euo pipefail

version=$1
tar_path=$(realpath "$2")
output=$(realpath -m "$3")
packaging=$(dirname "$(realpath "$0")")

key=$(gpg --list-secret-keys --with-colons | awk -F: '$1 == "fpr" { print $10; exit }')
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

sed "s/^pkgver=.*/pkgver=$version/" "$packaging/PKGBUILD" > "$work/PKGBUILD"
cp "$tar_path" "$work/blinds.tar"
(cd "$work" && PACKAGER='Daliborlol <122283329+Daliborlol@users.noreply.github.com>' PKGEXT='.pkg.tar.zst' makepkg --nodeps --force --noconfirm)

mkdir -p "$output"
package=$(basename "$work"/blinds-*.pkg.tar.zst)
cp "$work/$package" "$output/"
cd "$output"
gpg --batch --yes --detach-sign --no-armor --local-user "$key" "$package"

repo-add --sign --key "$key" "$work/blinds.db.tar.gz" "$package"
# GitHub releases can't hold symlinks, and pacman asks for blinds.db and blinds.files by name.
for name in db db.sig files files.sig; do
    cp -L "$work/blinds.$name" "$output/blinds.$name"
done
