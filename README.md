# CanZE (Legacy Fork)

## About This Fork

This repository is a fork of the original CanZE project found at [https://github.com/fesch/CanZE](https://github.com/fesch/CanZE).

Upstream development discontinued active support for the Fluence Z.E. in 2019. This fork was created to preserve and restore Fluence Z.E. functionality, completely revamp the user interface into a modern dark cyber-cockpit theme, and integrate a real-time procedural V8 engine sound simulator driven by electric vehicle telemetry.

The application is configured with the application ID `lu.fisch.canze.legacy` and named **CanZE (Legacy)**, allowing it to be installed and run side-by-side with official CanZE builds without conflicts.

---

## What's New in This Fork

### 1. Modern UI / Cyber-Cockpit Overhaul
The legacy layout architecture (plain Android tables, stark grey boxes, and white backgrounds) has been redesigned with a modern dark cockpit theme:

- **Card-Based UI Architecture**: Clean dark background (`#0B0F19`) with rounded surface cards (`#161D2B`), glowing neon accent indicators, and smooth entrance animations across all updated screens.
- **Modernized Screens**:
  - **Motor Menu (`MotorActivity`)**: High-rate cockpit readout with a live accelerator pedal gauge, motor wheel torque bar with distinct drive (magenta) and regenerative braking (cyan) states, digital speedometer, and interactive 2x2 control dock.
  - **Dashboard (`DashActivity`)**: Digital speedometer with 2-decimal precision, battery state of charge (SoC natively read from passive frame `42E.0`), PRND transmission gear indicator, battery pack temperature, and air conditioning status.
  - **Battery (`BatteryActivity`)**: Redesigned hero cards for available range and energy, State of Health (SOH), real vs. indicated SoC delta, cell voltage extremes (Min, Max, Δ mV), and an interactive 4/12-module temperature heatmap.
  - **Charging (`ChargingActivity`)**: Modern charging power, DC power inflow, available range, and battery telemetry cards with loading spinners for pending diagnostic queries.
  - **Climate (`ClimateActivity`)**: Modern HVAC dashboard replacing legacy ClimaTech with blower fan gauge, airflow/flap distribution controls, cabin/outside temperatures, and AC line pressure sensors.
  - **Powertrain (`PebActivity`)**: Diagnostic readout for odometer, 12V and high-voltage converter voltages, inverter and rotor temperatures, HV traction current, and ignition status.
  - **Consumption (`ConsumptionActivity`)**: Dual wheel torque progress bars (accelerative torque vs. regenerative braking), instant consumption splits, and responsive dashboard layout modes.
- **Modern Canvas Widgets**: Custom vector drawing widgets (`Timeplot`, `Plotter`, `Label`) updated to render as window cards with rounded corners, translucent area fills, glow lines, and live pack telemetry pills.

### 2. Procedural V8 Motor Sound Simulator
An authentic procedural V8 engine sound synthesizer that translates electric vehicle pedal and motor dynamics into an internal combustion soundtrack:

- **Engine-Sim DSP Signal Chain**:
  - Accurate **cross-plane V8 firing order** (1-8-4-3-6-5-7-2) over 720° crank cycles with asymmetrical bank pulses and valve overlap chuff.
  - **Partitioned FFT Convolution**: Models exhaust pipe acoustics using impulse responses (`v8_ir.wav` asset or dynamically synthesized comb/all-pass pipe networks).
  - **Acoustic Nuance**: Camshaft idle lope, crank angle flutter, jitter filter, derivative bite, intake flutter, and decel overrun crackles/pops during regenerative braking.
  - **Flywheel Physics**: Rotational inertia with authentic 0–1000 RPM starter sweep, heavy flywheel decel rev hang, and a smooth 2.2s exhaust spin-down on ignition cut.
- **Simulated 7-Speed Transmission**:
  - Progressive gear ratios with smooth shift cuts and rev-matched downshift throttle blips.
  - Cruise overdrive upshifting and throttle-delayed shift schedules to prevent gear hunting.
  - Launch flare with torque converter slip simulation.
- **Cockpit Controls & Standalone Simulator**:
  - Interactive **Tachometer (`RpmGaugeView`)** with sweep needle, redline warning arc, and virtual gear badge.
  - Dock buttons: **Ignition (On/Off)**, **Sound (Mute/Unmute)**, **Settings**, and **Test Drive**.
  - **Test Drive Mode**: Realistic tractive effort vs. road-load physics simulation allowing standalone sound verification without a car.
  - **Disconnected Sliders**: On-screen simulation sliders for speed, pedal, and torque appear automatically when disconnected or in debug mode.
  - **Audio Settings Dialog**: Live persistent sliders for master volume, idle RPM, stall flash, upshift points, combustion bite, overrun pop rate, and AGC leveling targets, with a one-click reset to defaults.

### 3. OBD Polling & Diagnostics Improvements
- **Automatic PID Blacklisting**: Diagnostic frames (PIDs) that fail to respond three times consecutively are automatically blacklisted from the polling loop to prevent non-existent PIDs from stalling communications. Skipped values are highlighted in red in the UI with a notification banner, and the blacklist can be cleared from Settings.
- **ELM327 Stream Resynchronization**: Active prompt draining and ATMA/ATCRA filter caching eliminate buffer desynchronization and reduce polling latency down to 15–25 Hz for real-time pedal tracking.

---

## Compatibility & Hardware

- **Tested Vehicle**: Renault Fluence Z.E.
- **Other Vehicles**: Built on top of the original CanZE codebase for Renault Z.E. vehicles (ZOE, Kangoo, Twizy). The V8 sound engine relies on standard CAN frames (`186` for pedal position / motor torque and `5D7` for vehicle speed). While designed and tuned around Fluence Z.E. response profiles, it may function on other models supporting these standard frames. Unrecognized diagnostic frames are automatically blacklisted to maintain responsiveness.
- **OBD Adapters**: Quality Bluetooth ELM327 adapters supporting CAN 500kbps 11-bit (SP6) or compatible hardware (e.g. Bob Due, OBDLink).

---

## Upstream Project

You have to read and agree to the informal warning and the formal disclaimer at the end of this readme file!

CanZE is an Android App that allows you to read out useful information from your Renault Z.E. car (Zoe, Kangoo & Fluence) that you cannot access using the on-board computer or display.

The original website can be found at [http://canze.fisch.lu](http://canze.fisch.lu) and upstream repository at [https://github.com/fesch/CanZE](https://github.com/fesch/CanZE).

## Issues and Language

For bugs or requests related to this fork, please use the GitHub Issues tracker for this repository. Reports in English, German, French, Portuguese, Dutch, and Danish are welcome (responses will generally be in English).

## Informal Warning

Before you download and use this software consider the following:
you are interfering with your car and doing that with hardware and software beyond your control (and frankly, for
a large part beyond ours), created by a loose team of interested amateurs in this field. Any car is a possibly
lethal piece of machinery and you might hurt or kill yourself or others using it, or even paying attention to
the displays instead of watching the road. Be extremely prudent!

By even downloading this software, or the source code provided on github, you agree to have completely understood this.

## Formal Disclaimer

CANZE (“THE SOFTWARE”) IS PROVIDED AS IS. USE THE SOFTWARE AT YOUR OWN RISK. THE AUTHORS MAKE NO WARRANTIES AS TO
PERFORMANCE OR FITNESS FOR A PARTICULAR PURPOSE, OR ANY OTHER WARRANTIES WHETHER EXPRESSED OR IMPLIED. NO ORAL OR
WRITTEN COMMUNICATION FROM OR INFORMATION PROVIDED BY THE AUTHORS SHALL CREATE A WARRANTY. UNDER NO CIRCUMSTANCES
SHALL THE AUTHORS BE LIABLE FOR DIRECT, INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES RESULTING FROM THE
USE, MISUSE, OR INABILITY TO USE THE SOFTWARE, EVEN IF THE AUTHOR HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH
DAMAGES. THESE EXCLUSIONS AND LIMITATIONS MAY NOT APPLY IN ALL JURISDICTIONS. YOU MAY HAVE ADDITIONAL RIGHTS AND
SOME OF THESE LIMITATIONS MAY NOT APPLY TO YOU. THIS SOFTWARE IS ONLY INTENDED FOR SCIENTIFIC USAGE.
