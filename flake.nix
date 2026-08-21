{
  description = "A clj-nix flake";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    clj-nix.url = "github:jlesquembre/clj-nix";
  };

  outputs = { self, nixpkgs, flake-utils, clj-nix }:

    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
        {
          devShells.default =
            pkgs.mkShell {
              buildInputs = with pkgs;[ clojure ];
            };
          packages.default = clj-nix.lib.mkCljApp {
            inherit pkgs;
            modules = [
              { # Option list: https://jlesquembre.github.io/clj-nix/options/
                projectSrc = ./.;
                version = "0.0.1";
                name = "paul.victor/keyfreq";
                main-ns = "keyfreq.core";
              }
            ];
          };


        }) // {
      overlays.default = import ./nix/overlay.nix { inherit clj-nix; };
      nixosModules.default = import ./nix/module.nix;
    };
}
