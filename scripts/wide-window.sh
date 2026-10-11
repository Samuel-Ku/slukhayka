#!/usr/bin/env bash
#
# Make a connected phone's window WIDE in dp by overriding its density.
#
# The density is derived from the device's own resolution and the width asked
# for — never copied out of a document:
#
#     density = 160 * width_px / target_dp
#
# The result is FLOORED on purpose. A density rounded up is a narrower window
# than asked for — `160 * width_px / (density + 1)` is below the target — and a
# number that is one dpi too coarse turns «the panel did not appear» into a
# mystery. The width this script prints comes from the same two numbers Android
# reads `screenWidthDp` from, so it is the answer to «did I cross the line I am
# testing», not a promise about it.
#
# Usage:
#   scripts/wide-window.sh <target-dp> [--serial <device>]
#   scripts/wide-window.sh --density-for <width-px> <target-dp>
#
# Exit: 0 the density is applied (or was already in place); 1 the request could
# not be answered at all — an adb call failed, `wm size` held no WxH to read, or
# the target is wider than the panel can be at any density; 2 bad arguments;
# 3 no usable device, or no adb to reach one with.
set -u

adb=${SLUKHAYKA_ADB:-adb}
serial=""
target_dp=""

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/wide-window.sh <target-dp> [--serial <device>]
       scripts/wide-window.sh --density-for <width-px> <target-dp>

Overrides the connected device's density so its window is at least <target-dp>
dp wide, prints the width that results, and reminds you to reset it.

  <target-dp>        the window width in dp you want to reach
  --serial <device>  name the device when more than one is connected
  --density-for <width-px> <target-dp>
                     print the density alone; needs no device at all
USAGE
}

is_positive_integer() {
  [[ ${1:-} =~ ^[0-9]+$ ]] && (( $1 > 0 ))
}

comma_list() {
  local joined
  joined=$(printf '%s,' "$@")
  printf '%s\n' "${joined%,}"
}

density_for() {
  # density_for <width-px> <target-dp> — the density at which <width-px> spans
  # AT LEAST <target-dp> dp. Pure: no device, no adb, no state. Floored, see
  # the header.
  printf '%s\n' "$(( 160 * $1 / $2 ))"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      [[ $# -ge 2 ]] || { usage; exit 2; }
      serial=$2
      shift 2
      ;;
    --density-for)
      if [[ $# -lt 3 ]] || ! is_positive_integer "$2" || ! is_positive_integer "$3"; then
        printf '%s\n' "--density-for needs a positive width in pixels and a positive target in dp" >&2
        usage
        exit 2
      fi
      density_for "$2" "$3"
      exit 0
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      if [[ $1 =~ ^-[0-9]+$ ]]; then
        printf '%s\n' "<target-dp> must be a positive whole number of dp." >&2
        exit 2
      fi
      printf '%s\n' "Unknown option: $1" >&2
      usage
      exit 2
      ;;
    *)
      if [[ -n $target_dp ]]; then
        printf '%s\n' "One target width per run; got '$target_dp' and '$1'." >&2
        exit 2
      fi
      target_dp=$1
      shift
      ;;
  esac
done

if [[ -z $target_dp ]] || ! is_positive_integer "$target_dp"; then
  printf '%s\n' "<target-dp> must be a positive whole number of dp." >&2
  usage
  exit 2
fi

if ! command -v "$adb" >/dev/null 2>&1; then
  printf '%s\n' "No adb to talk to a device with (looked for '$adb'), so nothing can be applied here." >&2
  printf '%s\n' "A machine with adb reads the width off the device:" >&2
  printf '%s\n' "  adb shell wm size        # Physical size / Override size, in pixels" >&2
  printf '%s\n' "With that width, the arithmetic alone runs without a device:" >&2
  printf '%s\n' "  scripts/wide-window.sh --density-for <width-px> $target_dp" >&2
  exit 3
fi

devices=$("$adb" devices 2>&1) || {
  printf '%s\n' "adb devices failed:" "$devices" >&2
  exit 1
}
available=()
while read -r found; do
  [[ -n $found ]] && available+=("$found")
done < <(printf '%s\n' "$devices" | awk 'NR > 1 && $2 == "device" { print $1 }')
blocked=()
while read -r found state; do
  [[ -n $found ]] && blocked+=("$found ($state)")
done < <(printf '%s\n' "$devices" | awk 'NR > 1 && $2 ~ /^(unauthorized|offline|bootloader|recovery|sideload)$/ { print $1, $2 }')

if [[ ${#available[@]} -eq 0 ]]; then
  if [[ ${#blocked[@]} -gt 0 ]]; then
    printf '%s\n' "No usable device. adb does see $(comma_list "${blocked[@]}") — 'unauthorized' means the phone is waiting for the USB-debugging prompt, 'offline' that it is not answering yet." >&2
  else
    printf '%s\n' "No device: 'adb devices' lists none, so there is no resolution to derive a density from." >&2
  fi
  printf '%s\n' "The width has to come from the device itself:" >&2
  printf '%s\n' "  adb shell wm size        # Physical size / Override size, in pixels" >&2
  printf '%s\n' "With that width, the arithmetic alone runs without a device:" >&2
  printf '%s\n' "  scripts/wide-window.sh --density-for <width-px> $target_dp" >&2
  exit 3
fi

if [[ -n $serial ]]; then
  if ! printf '%s\n' "${available[@]}" | grep -qxF -- "$serial"; then
    printf '%s\n' "--serial $serial is not a connected device; adb lists: $(comma_list "${available[@]}")" >&2
    exit 3
  fi
elif [[ ${#available[@]} -gt 1 ]]; then
  printf '%s\n' "More than one device is connected: $(comma_list "${available[@]}")" >&2
  printf '%s\n' "Name the one to change with --serial <device>: a density override applied to the wrong phone is a phone nobody meant to touch." >&2
  exit 3
else
  serial=${available[0]}
fi

size_report=$("$adb" -s "$serial" shell wm size 2>&1) || {
  printf '%s\n' "adb -s $serial shell wm size failed:" "$size_report" >&2
  exit 1
}
size=$(printf '%s\n' "$size_report" | awk '
  /^Physical size: / { physical = $3 }
  /^Override size: / { override = $3 }
  END { print (override != "" ? override : physical) }')
if [[ ! $size =~ ^[0-9]+x[0-9]+$ ]]; then
  printf '%s\n' "No WxH size in the device's report, so the width is unknown:" "$size_report" >&2
  printf '%s\n' "Read it by hand with 'adb -s $serial shell wm size'." >&2
  exit 1
fi
size_width=${size%x*}
size_height=${size#*x}
if (( size_width > size_height )); then
  narrow_px=$size_height
else
  narrow_px=$size_width
fi

density_report=$("$adb" -s "$serial" shell wm density 2>&1) || {
  printf '%s\n' "adb -s $serial shell wm density failed:" "$density_report" >&2
  exit 1
}
physical_density=$(printf '%s\n' "$density_report" | awk '/^Physical density: / { print $3 }')
current_override=$(printf '%s\n' "$density_report" | awk '/^Override density: / { print $3 }')

density=$(density_for "$narrow_px" "$target_dp")
if (( density < 1 )); then
  printf '%s\n' "$target_dp dp is wider than a $narrow_px px panel can be at any density: the arithmetic asks for less than 1 dpi." >&2
  exit 1
fi

applied=""
if [[ -n $current_override && $current_override == "$density" ]]; then
  applied=$current_override
  printf '%s\n' "Density $density dpi is already the device's override; nothing to apply."
elif [[ -z $current_override && -n $physical_density && $physical_density == "$density" ]]; then
  applied=$physical_density
  printf '%s\n' "The device's own $physical_density dpi already gives at least $target_dp dp on $narrow_px px; no override needed."
else
  printf '%s\n' "Applying density $density dpi: adb -s $serial shell wm density $density"
  if ! set_report=$("$adb" -s "$serial" shell wm density "$density" 2>&1); then
    printf '%s\n' "adb -s $serial shell wm density $density failed:" "$set_report" >&2
    printf '%s\n' "Nothing was widened. If the device did take an override anyway, undo it: adb -s $serial shell wm density reset" >&2
    exit 1
  fi
fi

final_report=$("$adb" -s "$serial" shell wm density 2>&1) || {
  printf '%s\n' "adb -s $serial shell wm density failed:" "$final_report" >&2
  exit 1
}
final_override=$(printf '%s\n' "$final_report" | awk '/^Override density: / { print $3 }')
final_physical=$(printf '%s\n' "$final_report" | awk '/^Physical density: / { print $3 }')
[[ -n $applied ]] || applied=${final_override:-$final_physical}

printf '%s\n' "Device $serial: $size px; the narrow side is $narrow_px px (a portrait window's width)."
if [[ -n $final_override ]]; then
  printf '%s\n' "Density now: $final_override dpi override on ${final_physical:-unknown} dpi physical."
else
  printf '%s\n' "Density now: ${final_physical:-unknown} dpi, no override."
fi

if [[ -n $applied ]]; then
  dp_tenths=$(( 1600 * narrow_px / applied ))
  printf '%s\n' "Window: $narrow_px px / $applied dpi ≈ $(( dp_tenths / 10 )).$(( dp_tenths % 10 )) dp (asked for at least $target_dp)."
  if (( dp_tenths / 10 < target_dp )); then
    printf '%s\n' "WARNING: that is below $target_dp dp — the device reports a density other than the one derived here, so the window did not reach the width asked for." >&2
  fi
else
  printf '%s\n' "The device reported no density, so the width in dp cannot be worked out here; read it with 'adb -s $serial shell wm density'." >&2
fi

if [[ -n $final_override ]]; then
  printf '%s\n' "The running app keeps the layout it started with; restart it to see the new one (debug package): adb -s $serial shell am force-stop com.slukhayka.audiobooks.debug"
  printf '%s\n' "Undo when the run is over: adb -s $serial shell wm density reset"
else
  printf '%s\n' "No density override is set, so there is nothing to reset."
fi
