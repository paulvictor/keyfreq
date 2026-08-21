{ clj-nix }: final: prev: {
  keyfreq = clj-nix.lib.mkCljApp {
    pkgs = final;
    modules = [
      {
        projectSrc = ../.;
        version = "0.0.1";
        name = "paul.victor/keyfreq";
        main-ns = "keyfreq.core";
      }
    ];
  };
}
