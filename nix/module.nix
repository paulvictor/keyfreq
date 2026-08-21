{ config, lib, pkgs, ... }:

let
  cfg = config.services.keyfreq;

  wrapperScript = pkgs.writeShellScript "keyfreq-start" ''
    device=$(sed -n '/'"${cfg.keyboardName}"'/,/^$/p' /proc/bus/input/devices \
      | grep "^H:" \
      | grep -o 'event[0-9]*')

    if [ -z "$device" ]; then
      echo "Could not find keyboard device matching '${cfg.keyboardName}'" >&2
      exit 1
    fi

    exec ${pkgs.keyfreq}/bin/keyfreq \
      --keyboard "/dev/input/$device" \
      --directory "${cfg.dataDir}" \
      --bigrams-within "${toString cfg.bigramsWithin}"
  '';
in
{
  options.services.keyfreq = {
    enable = lib.mkEnableOption "keyfreq keyboard frequency tracker";

    keyboardName = lib.mkOption {
      type = lib.types.str;
      description = "Name of the keyboard device as it appears in /proc/bus/input/devices";
      example = "kanata";
    };

    dataDir = lib.mkOption {
      type = lib.types.str;
      default = "/var/lib/keyfreq";
      description = "Directory where keystroke log files will be stored";
    };

    bigramsWithin = lib.mkOption {
      type = lib.types.int;
      default = 500000;
      description = "Max time in microseconds between keystrokes to count as a bigram";
    };
  };

  config = lib.mkIf cfg.enable {
    users.users.keyfreq = {
      isSystemUser = true;
      group = "keyfreq";
      extraGroups = [ "input" ];
      home = cfg.dataDir;
      createHome = true;
    };

    users.groups.keyfreq = { };

    systemd.services.keyfreq = {
      description = "Keyboard frequency tracker";
      wantedBy = [ "multi-user.target" ];

      serviceConfig = {
        User = "keyfreq";
        WorkingDirectory = cfg.dataDir;
        ExecStart = "${wrapperScript}";
        Restart = "on-failure";
        RestartSec = "5s";
        TimeoutStopSec = "10s";
      };
    };
  };
}
