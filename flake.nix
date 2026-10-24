{
  description = "evoatmo - Android (Xposed) module development environment";

  inputs.nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0.1"; # unstable Nixpkgs

  outputs =
    { self, ... }@inputs:

    let
      javaVersion = 17; # Java 17 — matches the module build (compileOptions + kotlin jvmTarget, docs/setup.md §4.2)

      supportedSystems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forEachSupportedSystem =
        f:
        inputs.nixpkgs.lib.genAttrs supportedSystems (
          system:
          f {
            pkgs = import inputs.nixpkgs {
              inherit system;
              config = {
                allowUnfree = true;
                android_sdk.accept_license = true;
              };
              overlays = [ inputs.self.overlays.default ];
            };
          }
        );
    in
    {
      overlays.default =
        final: prev:
        let
          jdk = prev."jdk${toString javaVersion}";
        in
        {
          gradle = prev.gradle.override { java = jdk; };
          kotlin = prev.kotlin.override { jre = jdk; };
        };

      devShells = forEachSupportedSystem (
        { pkgs }:
        let
          jdk = pkgs."jdk${toString javaVersion}";
          androidComposition = pkgs.androidenv.composeAndroidPackages {
            # matches docs: compileSdk/targetSdk 36 (docs/setup.md §4.1)
            buildToolsVersions = [ "36.0.0" ];
            platformVersions = [ "36" ];
            includeEmulator = false;
          };
          androidSdk = androidComposition.androidsdk;
        in
        {
          default = pkgs.mkShellNoCC {
            packages =
              with pkgs;
              [
                jdk
                gradle
                kotlin
                androidSdk
                scrcpy # device screen mirroring (docs/operations daily loop)
                python3 # tools/py host tooling
                just
                ktlint
                ncurses
                zlib
              ]
              ++ pkgs.lib.optionals pkgs.stdenv.hostPlatform.isLinux [
                gcc
                patchelf
              ];

            JAVA_HOME = "${jdk}";
            ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
            ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";

            shellHook = ''
              export JAVA_HOME="${jdk}"
              # Prefer a locally installed Android SDK if present, falling back to the Nix-provided one
              if [ -z "$ANDROID_HOME" ]; then
                if [ -d "$HOME/Library/Android/sdk" ]; then
                  export ANDROID_HOME="$HOME/Library/Android/sdk"
                elif [ -d "$HOME/Android/Sdk" ]; then
                  export ANDROID_HOME="$HOME/Android/Sdk"
                else
                  export ANDROID_HOME="${androidSdk}/libexec/android-sdk"
                fi
              fi

              if [ -n "$ANDROID_HOME" ]; then
                export ANDROID_SDK_ROOT="$ANDROID_HOME"
                export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

                # Ensure local.properties points to a valid SDK directory
                if [ -f "local.properties" ]; then
                  current_sdk=$(grep -E '^sdk\.dir=' local.properties | cut -d'=' -f2-)
                  if [ -n "$current_sdk" ] && [ ! -d "$current_sdk" ]; then
                    sed -i.bak "s|^sdk\.dir=.*|sdk.dir=$ANDROID_HOME|" local.properties && rm -f local.properties.bak
                  fi
                else
                  echo "sdk.dir=$ANDROID_HOME" > local.properties
                fi
              fi
            '';
          };
        }
      );
    };
}
