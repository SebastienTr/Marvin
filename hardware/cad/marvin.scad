// =====================================================================
//  MARVIN - open-source desk companion, rev F (the upright robot)
//
//  Parametric OpenSCAD source. Units: mm. OpenSCAD >= 2021.01.
//  Frame: X = right, Y = back (+) / front (-, toward the user), Z = up.
//  Z = 0 is the desk. The robot stands upright; the sensors are tilted
//  inside it, not the body.
//
//  The body half (plinth, sensor sled, body, TPU neck) and the head (shell, back cap, face frame
//  with the XIAO pocket, smoked acrylic window, knob and switch bracket). The head <-> body
//  interface is frozen (the FROZEN block below); the head's module sizes are in the MEASURED (H0)
//  block, and hardware/scripts/check_head.py checks the fits.
//
//  Rev F has no lidar (since 14 October 2026): nothing turns on top of the head. The rev E
//  lidar template is in hardware/archive/rev-e/.
//
//  PART = "assembly" | "exploded" | "section" | "head_exploded"   (views)
//         "body" | "plinth" | "sled" | "neck" | "grommet" | "foot"   (assembly frame)
//         "head_shell" | "head_cap" | "face_frame" | "knob" | "switch_bracket" | "window"
//         "ghost_mr60" | "ghost_ld2450" | "ghost_speaker" | "ghost_lcd" | "ghost_xiao" | "ghost_usb" | "ghost_tact"
//         "print_body" | "print_plinth" | "print_sled" | "print_tpu" | "print_head_shell" | "print_head_cap"
//         "print_face_frame" | "print_knob" | "print_switch_bracket" | "print_window_jig"
//         "window_2d" (DXF) | "check_values" | "clash" (CLASH_A x CLASH_B) | "none"
//
//  SPDX-License-Identifier: CERN-OHL-P-2.0
// =====================================================================

PART = "assembly";
$fn = 64;
eps = 0.01;

// ---------------- FROZEN: head <-> body interface -------------------------
// Marvin v1 contract C8 (frozen 14 October 2026). The head CAD builds on these
// values and the body is not changed: change them only through the integrator,
// together with docs/architecture.md (Coordinate frame, extrinsics table).
headW = 88;  headD = 80;  headR = 16;     // head footprint (squircle)
headZ0 = 68; headZ1 = 124;                // head bottom (floor) / top
// Neck: 4 x M3 through TPU grommets, up into pilots in the head floor
neckBolt = [[-32, -20], [32, -20], [-32, 20], [32, 20]];
cableHole = [30, 16, 5];                  // w, d, corner r; the 90 deg USB-C plug passes through it
cableHoleY = 4;
camPos = [0, -38, 113];                   // camera lens centre (XIAO ESP32S3 Sense), looks toward -Y...
camTilt = 20;                             // ...tilted 20 deg up
screenCentre = [0, -38, 92];              // centre of the 1.69" screen's active area, vertical, faces -Y
// ---------------- end of FROZEN ------------------------------------------

// ---------------- Outer shape -------------------------------------------
bodyW = 80;  bodyD = 74;  bodyR = 16;     // body footprint (squircle), a little narrower than the head
bodyZ0 = 6;  bodyZ1 = 64;                 // body bottom / top (the plinth hides below bodyZ0)
wall = 1.6;                               // half a wavelength at 60 GHz in PETG: the front wall is a radome
deck = 2.0;                               // roof of the body, under the neck
neckH = headZ0 - bodyZ1;                  // 4 mm TPU gasket between body and head

// Anthracite band: a filament change by layer (cosmetic only; any colour is RF-transparent)
bandZ0 = 20; bandZ1 = 50;

plinthW = 68; plinthD = 62; plinthR = 12; // recessed foot, reads as a shadow line
floorT = 2.0;                             // plinth plate that closes the body from below
floorZ = bodyZ0 + floorT;                 // = 8, everything inside stands on it

// ---------------- Fits and fasteners -------------------------------------
clr = 0.3;
m3_clear = 3.4; m3_pilot = 2.6; m3_head = 6.2; m25_pilot = 2.1;
foam = 3.0;                               // double-sided foam pads under every module

// ---------------- Inside the body ------------------------------------------
inW = bodyW - 2*wall;  inD = bodyD - 2*wall;  inR = bodyR - wall;
yWall  = -inD/2;                          // inner face of the front wall
yFront = yWall + 1.0;                     // module fronts stand 1 mm behind the wall

// 24 GHz radar HLK-LD2450: low, tilted 10 deg up. It sits BELOW the 60 GHz radar so it never
// shadows the 60 GHz beam, which leaves the robot upwards.
ldW = 44; ldH = 15; ldT = 1.6; ldTilt = 10;
ldZ = floorZ + 1.0;                       // bottom edge of the board (rests on a 1 mm lip)

// 60 GHz radar MR60BHA2 kit in Seeed's 54 x 35 x 22 case, landscape, tilted 20 deg up
kitW = 54; kitH = 35; kitD = 22; kitTilt = 20;
kitZ = ldZ + ldH*cos(ldTilt) + 1.5;       // bottom front edge of the case, just above the LD2450

// Sled: four ribs (they carry both radars), a foot and a back plate for the lever connectors and the amp
ribX  = [[14, 20], [23, 27]];             // |x| ranges of the inner and outer ribs
bpT = 3;  bpW = 60;                       // back plate
yBP = inD/2 - clr - bpT;                  // front face of the back plate
sledTop = bodyZ1 - deck - 2;              // 2 mm under the deck
screwX = 17;                              // plinth screws (from below) and back-wall screws (from behind) go into the inner ribs
plinthScrewY = 8;
backScrewZ = [26, 46];

// Speaker 2030 (20 x 30 x 5) glued inside the right wall behind a dot grille
spkY = 0; spkZ = 18; spkL = 30.4; spkH = 20.4;

// Neck: 4 x M3 through TPU grommets (neckBolt, FROZEN above). The screws go up into the head floor,
// so no rigid path links the head (knocks, a tap on the knob) to the body (heart-rate radar).
gromOD = 7.6; gromFlange = 11; gromFlangeT = 1.6; gromBore = m3_clear;
bossT = 1.2;                              // flat seat for the grommet flange under the deck

// =====================================================================
// Helpers
function rot(v, a) = [v[0]*cos(a) - v[1]*sin(a), v[0]*sin(a) + v[1]*cos(a)];
function add(a, b) = [a[0]+b[0], a[1]+b[1]];
function mul(v, k) = [v[0]*k, v[1]*k];

module squircle(w, d, r) { offset(r=r) square([w - 2*r, d - 2*r], center=true); }
module sqblock(w, d, r, z0, h) { translate([0, 0, z0]) linear_extrude(h) squircle(w, d, r); }
module rrect(w, d, r, h) { linear_extrude(h) offset(r=r) square([w - 2*r, d - 2*r], center=true); }
// a 2-D profile drawn in the (y, z) side plane, extruded along X from x0 to x1
module sideExtrude(x0, x1) { translate([x0, 0, 0]) rotate([90, 0, 90]) linear_extrude(x1 - x0) children(); }

// ---------------- Radar geometry in the side plane (y, z) --------------------
// "up" follows the front face of a module tilted by t; "in" goes from its front face into the robot
function upV(t) = [sin(t), cos(t)];
function inV(t) = [cos(t), -sin(t)];
L0 = [yFront, ldZ];                                         // LD2450 front, bottom edge
K0 = [yFront, kitZ];                                        // MR60 case front, bottom edge
kitCentre = add(K0, mul(upV(kitTilt), kitH/2));
ldCentre  = add(L0, mul(upV(ldTilt), ldH/2));
echo(str("MR60BHA2 face centre: y=", kitCentre[0], " z=", kitCentre[1], " | LD2450 face centre: y=", ldCentre[0], " z=", ldCentre[1]));

// Rib profile: lip under the LD2450, foam line behind it, ledge under the MR60 case,
// foam line behind the case, then straight back to the back plate.
function ribProfile() = let(
    sL0 = add(L0, mul(inV(ldTilt), ldT + foam)),            // LD2450 support line, bottom
    sL1 = add(sL0, mul(upV(ldTilt), ldH - 1)),               // ... top
    ledgeZ = function (y) kitZ - (y - yFront) * tan(kitTilt),
    e1 = [sL1[0], ledgeZ(sL1[0])],                           // up to the MR60 ledge
    f1 = add(K0, mul(inV(kitTilt), kitD + foam)),            // end of the ledge (case back + foam)
    f2 = add(f1, mul(upV(kitTilt), kitH - 3))                // top of the case support
) [[yFront, floorZ], [yFront, ldZ - 0.2], [sL0[0], ldZ - 0.2 - (sL0[0]-yFront)*tan(ldTilt)],
   sL1, e1, f1, f2, [yBP + eps, f2[1]], [yBP + eps, floorZ]];

// =====================================================================
// BODY - upright shell with the deck on top. Print upside down (deck on the bed), no supports.
module body() {
    difference() {
        union() {
            difference() {
                sqblock(bodyW, bodyD, bodyR, bodyZ0, bodyZ1 - bodyZ0);
                sqblock(inW, inD, inR, bodyZ0 - eps, bodyZ1 - deck - bodyZ0 + eps);
            }
            // bosses under the deck for the grommet flanges to bear on (keep the deck flat and stiff)
            for (p = neckBolt) translate([p[0], p[1], bodyZ1 - deck - bossT]) cylinder(d=gromFlange + 3, h=bossT + eps);
        }
        // neck bolt holes (grommet shafts) and the cable hole
        for (p = neckBolt) translate([p[0], p[1], bodyZ1 - 5]) cylinder(d=gromOD + 0.4, h=10);
        translate([0, cableHoleY, bodyZ1 - 5]) rrect(cableHole[0], cableHole[1], cableHole[2], 10);
        // back-wall screws into the sled
        for (s=[-1,1], z=backScrewZ) translate([s*screwX, bodyD/2 - wall - 1, z]) rotate([-90, 0, 0]) cylinder(d=m3_clear, h=wall + 2, $fn=24);
        // speaker grille, right side: 6 x 2 dots
        for (i=[0:5], j=[0:1]) translate([bodyW/2 - wall - 1, spkY - 12.5 + i*5, spkZ - 2.5 + j*5]) rotate([0, 90, 0]) cylinder(d=2.0, h=wall + 2, $fn=16);
    }
    // speaker locating rails on the right wall (the speaker drops in from below)
    for (s=[-1,1]) translate([inW/2 - 6, spkY + s*(spkL/2 + clr) + (s > 0 ? 0 : -1.2), spkZ - spkH/2 - 1]) cube([6 + eps, 1.2, spkH + 2]);
}

// PLINTH - recessed foot + floor plate that closes the body. Two M3 screws hold the sled on it.
module plinth() {
    difference() {
        union() {
            sqblock(plinthW, plinthD, plinthR, 0, bodyZ0 + eps);
            sqblock(inW - 2*clr, inD - 2*clr, inR - clr, bodyZ0, floorT);
        }
        for (s=[-1,1]) translate([s*screwX, plinthScrewY, -1]) {
            cylinder(d=m3_clear, h=20, $fn=24);
            cylinder(d=m3_head, h=1 + 3, $fn=32);                          // counterbore from below
        }
        // USB-C cable leaves at the back, hidden under the body overhang
        translate([-4, inD/2 - 14, -1]) cube([8, 20, floorZ + 2]);
        // four shallow pockets for TPU or silicone feet
        for (sx=[-1,1], sy=[-1,1]) translate([sx*(plinthW/2 - 11), sy*(plinthD/2 - 11), -1]) cylinder(d=10.4, h=1 + 1.0);
    }
}

// SLED - carries both radars; the lever connectors and the amp stick on the back plate.
// Stands on the plinth; print upright as installed (foot on the bed), no supports.
module sled() {
    difference() {
        union() {
            for (r = ribX, s = [-1,1]) {
                x0 = s > 0 ? r[0] : -r[1];
                sideExtrude(x0, x0 + (r[1] - r[0])) polygon(ribProfile());
            }
            // back plate
            translate([-bpW/2, yBP, floorZ]) cube([bpW, bpT, sledTop - floorZ]);
            // foot: ties the ribs together behind the 60 GHz case
            translate([-bpW/2 + 3, -8, floorZ]) cube([bpW - 6, yBP - (-8) + eps, 3]);
        }
        // pilot holes: plinth screws from below, back-wall screws from behind
        for (s=[-1,1]) translate([s*screwX, plinthScrewY, floorZ - 1]) cylinder(d=m3_pilot, h=12, $fn=24);
        for (s=[-1,1], z=backScrewZ) translate([s*screwX, yBP + bpT + 1, z]) rotate([90, 0, 0]) cylinder(d=m3_pilot, h=14, $fn=24);
        // cable window in the back plate (open at the top)
        translate([-9, yBP - 1, sledTop - 18]) cube([18, bpT + 2, 20]);
        // keep the sled inside the body with clearance
        difference() {
            translate([-100, -100, 0]) cube([200, 200, 200]);
            sqblock(inW - 2*clr, inD - 2*clr, inR - clr, 0, 200);
        }
    }
}

// NECK - TPU 95A gasket between body and head (the visible dark gap)
module neck() {
    difference() {
        sqblock(bodyW - 10, bodyD - 10, 12, bodyZ1, neckH);
        for (p = neckBolt) translate([p[0], p[1], bodyZ1 - 1]) cylinder(d=gromOD + 0.4, h=neckH + 2);
        translate([0, cableHoleY, bodyZ1 - 1]) rrect(cableHole[0] + 4, cableHole[1] + 4, cableHole[2] + 2, neckH + 2);
    }
}

// GROMMET - TPU top-hat: flange under the deck, shaft through deck and gasket, M3 screw inside
module grommet() {
    difference() {
        union() {
            cylinder(d=gromFlange, h=gromFlangeT);
            cylinder(d=gromOD, h=gromFlangeT + bossT + deck + neckH - 0.5);   // stops 0.5 mm under the head floor
        }
        translate([0, 0, -1]) cylinder(d=gromBore, h=20, $fn=24);
    }
}

module foot() cylinder(d=10, h=2.0);

// =====================================================================
// Ghosts (not printed): modules and the head envelope
module kitGhost() translate([-kitW/2, 0, 0]) sideExtrude(0, kitW)
    polygon([K0, add(K0, mul(upV(kitTilt), kitH)), add(add(K0, mul(upV(kitTilt), kitH)), mul(inV(kitTilt), kitD)), add(K0, mul(inV(kitTilt), kitD))]);
module ldGhost() translate([-ldW/2, 0, 0]) sideExtrude(0, ldW)
    polygon([L0, add(L0, mul(upV(ldTilt), ldH)), add(add(L0, mul(upV(ldTilt), ldH)), mul(inV(ldTilt), ldT)), add(L0, mul(inV(ldTilt), ldT))]);
module speakerGhost() translate([inW/2 - 5, spkY - spkL/2, spkZ - spkH/2]) cube([5, spkL, spkH]);
module ghosts(cut=false) {
    color("DimGray") kitGhost();
    color("SeaGreen") ldGhost();
}
// Print orientations
module print_body()     translate([0, 0, bodyZ1]) rotate([180, 0, 0]) body();
module print_plinth()   translate([0, 0, floorZ]) rotate([180, 0, 0]) plinth();   // floor plate on the bed: no ledge to bridge
module print_sled()     translate([0, 0, -floorZ]) sled();
module print_tpu() {                      // gasket + 4 grommets + 4 feet, one TPU plate
    translate([0, 0, -bodyZ1]) neck();
    for (i=[0:3]) translate([-30 + i*20, 50, 0]) grommet();
    for (i=[0:3]) translate([-30 + i*20, 66, 0]) foot();
}

// cut = true keeps only x < 0, to look at the section from the right
module half(cut) { if (cut) intersection() { children(); translate([-200, -200, -10]) cube([200, 400, 400]); } else children(); }

module assemblyView(e=0, cut=false) {
    color("#6f6c67") half(cut) plinth();
    // body in its three print colours: warm grey, anthracite band, warm grey
    for (b = [[0, bandZ0, "#d9d5cc"], [bandZ0, bandZ1, "#3a3a3c"], [bandZ1, 200, "#d9d5cc"]])
        color(b[2]) half(cut) translate([0, 0, e*1.4]) intersection() { body(); translate([-100, -100, b[0]]) cube([200, 200, b[1] - b[0]]); }
    color("Goldenrod") half(cut) translate([0, 0, e*1.4]) speakerGhost();   // speaker, glued in the body
    color("#c0582b") half(cut) translate([0, 0, e*0.5]) sled();
    color("#222") half(cut) translate([0, 0, e*2.0]) neck();
    color("#222") half(cut) for (p = neckBolt) translate([p[0], p[1], bodyZ1 - deck - bossT - gromFlangeT + e*1.4]) grommet();
    half(cut) translate([0, 0, e*0.5]) ghosts(cut || e > 0);
    half(cut) translate([0, 0, e*2.6]) headParts(e > 0 ? 4 : 0);
}

// the head, assembled; e > 0 pulls it apart along the assembly directions
module headParts(e=0) {
    color("#d9d5cc") headShell();
    color("#d9d5cc") translate([0, e*7.0, 0]) headCap();
    color("#3a3a3c") translate([0, e*4.2, 0]) faceFrame();
    color("#0d0f12", 0.85) translate([0, -e*1.8, 0]) windowAcrylic();
    color("#d9541a") translate([-e*1.6, 0, 0]) knob();
    color("#3a3a3c") translate([e*1.2, 0, 0]) switchBracket();
    translate([0, e*3.4, 0]) { lcdGhost(); lcdActive(); }
    translate([0, e*5.0, 0]) { color("#9aa0a6") xiaoGhost(); color("#555") usbGhost(); }
    translate([e*1.2, 0, 0]) color("WhiteSmoke") tactGhost();
}
module headExploded() headParts(14);

// =====================================================================
// HEAD - built on the FROZEN interface above. Every module dimension that the head depends on
// is in the MEASURED (H0) block below; everything else is derived from it.
// =====================================================================

// ---------------- MEASURED (H0) ------------------------------------------
// PROVISIONAL: these are published or typical values, not yet measured on Marvin's own parts.
// H0 replaces the numbers (calipers, 0.1 mm); nothing else in the file should need to change.
// hardware/scripts/check_head.py reports the fits; while H0_PROVISIONAL is true, a fit that
// depends on these numbers is a warning, not an error.
H0_PROVISIONAL = true;

// 1.69" IPS LCD module, 240 x 280, ST7789, generic SPI board (docs/bom.md), portrait, glass forward.
//   Active area 27.97 x 32.63 = 240 x 280 px at 0.11655 mm (Waveshare 1.69inch LCD Module page,
//   waveshare.com/product/displays/lcd-oled/1.69inch-lcd-module.htm; same panel at makerstore.com.au
//   "ELC TFT 1.69 ST7789"): exact, not measured.
//   Board 32 x 40 x ~4.5 mm overall (generic module, ifuturetech.org "1.69 inch full color SPI 240X280
//   ST7789V"); Waveshare's is 31.5 x 39. Panel outline 30.07 x 37.43 x 1.56 (typical bare 1.69" panel).
lcdPcbW = 32.0;   lcdPcbH = 40.0;  lcdPcbT = 1.6;     // board outline and thickness
lcdGlassW = 30.1; lcdGlassH = 37.4; lcdGlassT = 1.6;  // glass panel outline and thickness
lcdTapeT = 0.4;                           // adhesive between the panel and the board
lcdActW = 27.97;  lcdActH = 32.63;        // active area (exact)
lcdGlassDz = 0.0;                         // panel centre above the board centre
lcdActDz = 0.9;                           // active-area centre above the panel centre (wider border at the FPC end)
lcdBackH = 2.0;                           // tallest part on the back of the board (regulator, backlight FET)
lcdHdrEdge = -1;                          // 8-pin header along the bottom (-1) or top (+1) short edge, pins backwards
lcdHdrN = 8;      lcdHdrInset = 1.6;      // pin count; pin row centre from that edge
lcdFpcW = 13;                             // panel FPC folded round the board edge (header end), relief width

// Seeed XIAO ESP32S3 Sense (base board + camera expansion board), USB-C to the right (+X).
//   21 x 17.8 x 15 mm with the expansion board (Seeed product page, via core-electronics.com.au
//   "Seeed Studio XIAO ESP32S3 Sense"). Lens position and the expansion-board depth are not published.
xiaoL = 21.0;     xiaoW = 17.8;           // board outline (both boards)
xiaoStackH = 15.0;                        // lens front to the back face of the base board
xiaoExpFront = 7.5;                       // lens front to the front face of the expansion board (guess)
xiaoPcbT = 1.0;                           // base board thickness
xiaoLensU = 0.0;  xiaoLensV = 0.0;        // lens offset from the board centre: toward the USB end / toward the top edge
xiaoRow = 15.24;                          // the two header rows, centre to centre (breadboard 0.6")
camModW = 8.5;                            // camera module, square
camBarrelD = 6.0; camBarrelL = 2.0;       // lens barrel in front of the module
xiaoUsbW = 9.0;   xiaoUsbH = 3.2;         // USB-C receptacle, on the front of the base board

// USB-C cable with a 90 deg plug (docs/bom.md); overmould around the plug, cable leaves backwards
plugW = 12.5;     plugT = 7.0;  plugL = 15.0;  plugCableD = 4.2;

// 6 x 6 mm tact switch (ESP32 starter kit): body, height with the plunger, plunger, leg spacing
tactW = 6.0;      tactBodyH = 3.5;  tactH = 5.0;  tactPlungerD = 3.5;  tactLegs = [6.5, 4.5];

// Smoked acrylic sheet (docs/bom.md: 2.3 mm "transparent black")
acrT = 2.3;
// ---------------- end of MEASURED (H0) -------------------------------------

// ---------------- C8 fit ------------------------------------------------------
// With the published sizes above, the XIAO Sense stack (lens on the board, 17.8 mm across, tilted
// 20 deg) does not fit at camPos above a screen whose active area is centred on screenCentre, in a
// head whose top is at headZ1: the boards and the camera module run into the top of the screen
// module (hardware/scripts/check_head.py, check "c8-fit"). C8_AMEND = true builds the amendment
// proposed to the integrator (head top and camera higher, nothing else changes); false builds
// exactly the FROZEN interface. Only the head uses these two values; the body never does.
C8_AMEND = false;
hZ1 = C8_AMEND ? 133 : headZ1;
hCam = C8_AMEND ? [camPos[0], camPos[1], 122] : camPos;

// ---------------- Head shell ------------------------------------------------
hWall = 2.4;  hFloor = 2.4;  hTop = 2.4;  // 6 perimeters; the head is not in front of a radar
hTopR = 4;                                // outer fillet on the top edges
hzF = headZ0 + hFloor;                    // = 70.4, floor top
hzT = hZ1 - hTop;                      // = 121.6, inner face of the top
hyF = -headD/2;                           // = -40, front face
hySeam = headD/2 - headR;                 // = 24: the back cap starts where the plan corners start
inHW = headW/2 - hWall;                   // = 41.6, inner half width
hyFi = hyF + hWall;                       // = -37.6, inner face of the front wall
lipH = 2.0;  lipT = 1.0;                  // alignment lip of the shell, inside the cap

// Face window: smoked acrylic behind a 0.8 mm lip, held by the face frame
winLip = 0.8;
camHoleD = 6;                             // drilled in the acrylic, in front of the lens
// visible opening: from just under the active area to just above the camera hole
winOpenZ0 = screenCentre[2] - lcdActH/2 - 1.7;  winOpenZ1 = hCam[2] + camHoleD/2 + 1.5;
winOpenW = 50;  winOpenH = winOpenZ1 - winOpenZ0;  winOpenR = 6;
winCz = (winOpenZ0 + winOpenZ1)/2;        // window centre height (96 with the FROZEN values)
// the acrylic runs this far behind the opening's edge (at most 2.3; less if the top fillet needs the room)
acrOver = min(2.3, hZ1 - hTopR - 0.4 - winOpenZ1);
acrW = 56;  acrH = winOpenH + 2*acrOver;  acrR = 3;   // the cut window: the front is flat only for |x| <= headW/2 - headR = 28
yAcrF = hyF + winLip;                     // = -39.2, front of the acrylic
yAcrB = yAcrF + acrT;                     // = -36.9, back of the acrylic = front of the screen glass
camGap = 0.3;                             // lens front behind the acrylic

// Screen: active-area centre on screenCentre (x, z); glass against the acrylic
lcdGlassZ = screenCentre[2] - lcdActDz;
lcdPcbZ = lcdGlassZ - lcdGlassDz;
yPcbF = yAcrB + lcdGlassT + lcdTapeT;     // front face of the board
lcdFoam = 0.8;                            // 1 mm foam pad behind the board, squeezed
lcdPocketD = lcdGlassT + lcdTapeT + lcdPcbT + lcdFoam;

// Face frame (anthracite, printed upright): plate behind the acrylic, screen pocket, XIAO cradle
frRim = 2.0;                              // frame behind the board, and the board's bearing rim
yFB = yAcrB + lcdPocketD + frRim;         // back of the plate
frW = acrW;  frZ0 = hzF + 0.4;  frZ1 = winCz + acrH/2;  frR = 4;
frEarX = 34;  frEarZ = winCz;             // the two M3 x 8 into columns in the shell's front corners
yColEnd = yFB - 1.0;                      // end of those columns = front of the ears
earT = 3;
earX1 = frEarX + 3.2;
earZ0 = frEarZ - 6 - (earX1 - frW/2);         // 45 deg underside outside the plate

// XIAO cradle (part of the face frame): a U channel at camTilt, the board drops in from above,
// the USB-C plug then locks it. Local frame: x = u (to the USB end), y = w (backwards along the
// lens axis), z = v (up the board); origin at the lens front.
camLens = [hCam[0], yAcrB + camGap, hCam[2]];
crC = 0.15;  crCv = 0.3;  crWall = 1.6;
xbU = -xiaoLensU;  xbV = -xiaoLensV;      // board centre in the local frame
wUsb = xiaoStackH - xiaoPcbT - xiaoUsbH/2;
module camLocal() translate(camLens) rotate([-camTilt, 0, 0]) children();
function camToWorld(p) = camLens + [p[0], p[1]*cos(camTilt) + p[2]*sin(camTilt), -p[1]*sin(camTilt) + p[2]*cos(camTilt)];

// Knob: an orange button on the left side, pressing a 6 x 6 tact switch (D7, docs/design.md v1-1)
kY = 4;  kZ = 96;  kD = 18;  kOut = 6;    // axis position, shaft diameter, protrusion
kFlangeD = 21;  kStemD = 4;
kxWall = -inHW;                           // inner face of the left wall
kxFlange = kxWall + 0.1 + 2 + 0.8;        // inner face of the flange (cone 2 mm + flat 0.8)
kxStem = kxFlange + 2.8;                  // stem tip
kxPlate = kxStem + 0.15 + tactH;          // switch back = front face of the bracket plate
kzo = hWall - 0.1 + kOut;                 // knob outer face, from the wall's inner face
kxOuter = kxWall + 0.1 - kzo;
kBossZ = 14;  kBossD = 6;  kPlateT = 3;

// Back cap: four M3 x 10 from behind into columns of the shell
capPos = [[-27, hzF + 3.2], [27, hzF + 3.2], [-27, hzT - 3.2], [27, hzT - 3.2]];
capColD = 6.4;  capColY0 = -22;  capBossD = 10;

// Neck pilots in the head floor (FROZEN neckBolt), M3 x 14 from below: tip at z = 73.2
neckBossD = 7;  neckBossH = 5.6;

// ---------------- Head helpers ----------------------------------------------
module box(x0, x1, y0, y1, z0, z1) translate([x0, y0, z0]) cube([x1 - x0, y1 - y0, z1 - z0]);
// rounded rectangle in the XZ plane, centre (0, zc), from y0 over t backwards
module xzRect(w, h, r, y0, t, zc, x=0) translate([x, y0, zc]) rotate([-90, 0, 0])
    linear_extrude(t) offset(r=r) square([w - 2*r, h - 2*r], center=true);
// a column along Y at (x, z), with its front end ramped at 45 deg onto the nearest wall,
// so it prints without supports when the shell stands on its front face
module wallColumn(x, z, d, y0, y1, wall) {
    // wall: [dx, dz] unit direction from the column to the wall it leans on
    reach = d;
    hull() {
        translate([x, y0, z]) rotate([-90, 0, 0]) cylinder(d=d, h=y1 - y0);
        translate([x + wall[0]*d/2, y0 - reach, z + wall[1]*d/2]) rotate([-90, 0, 0]) cylinder(d=0.2, h=y1 - y0 + reach, $fn=8);
    }
}
// a vertical cylinder whose cross-section is a teardrop pointing to -Y (prints lying down)
module tearCyl(d, h) linear_extrude(h) hull() { circle(d=d); translate([0, -d/2*1.41]) square(0.01, center=true); }

// outer solid of the head: plan squircle, top edges filleted, bottom edge chamfered
module headSolid() {
    hull() {
        sqblock(headW - 1.2, headD - 1.2, headR - 0.6, headZ0, 0.01);
        sqblock(headW, headD, headR, headZ0 + 0.6, hZ1 - hTopR - headZ0 - 0.6);
        for (a = [0:10:90]) let(k = hTopR*(1 - cos(a)))
            sqblock(headW - 2*k, headD - 2*k, headR - k, hZ1 - hTopR + hTopR*sin(a) - 0.01, 0.01);
    }
}
module headHollow() sqblock(headW - 2*hWall, headD - 2*hWall, headR - hWall, hzF, hzT - hzF);
// alignment lip: two strips on the inside of the side walls that slide 2 mm into the cap
// (only on the sides: the cap's screws and columns sit on the floor and under the top)
lipZ = [hzF + 10, hzT - 10];
module innerBlock(k, y0, y1) intersection() {
    sqblock(headW - 2*hWall - 2*k, headD - 2*hWall - 2*k, headR - hWall - k, lipZ[0], lipZ[1] - lipZ[0]);
    box(-60, 60, y0, y1, 0, 200);
}
module lipRing() {
    difference() { innerBlock(0.4, hySeam - eps, hySeam + lipH); innerBlock(0.4 + lipT, hySeam - 4, hySeam + lipH + 1); }
    difference() { innerBlock(0, hySeam - 3, hySeam); innerBlock(0.4 + lipT, hySeam - 4, hySeam + 1); }
}
module lipCut(c) difference() {
    intersection() { innerBlock(-0.01, hySeam - 1, hySeam + lipH + c); box(-60, 60, -60, 60, lipZ[0] - c, lipZ[1] + c); }
    innerBlock(0.4 + lipT + c, hySeam - 2, hySeam + lipH + 2);
}

// ---------------- Ghosts (not printed) ---------------------------------------
module lcdGhost(c=0) {
    hx = lcdHdrN*2.54/2;
    hz = lcdPcbZ + lcdHdrEdge*(lcdPcbH/2 - lcdHdrInset);
    color("#101418") box(-lcdGlassW/2 - c, lcdGlassW/2 + c, yAcrB - c, yAcrB + lcdGlassT + c, lcdGlassZ - lcdGlassH/2 - c, lcdGlassZ + lcdGlassH/2 + c);
    color("#1d5c3a") box(-lcdPcbW/2 - c, lcdPcbW/2 + c, yPcbF - c, yPcbF + lcdPcbT + c, lcdPcbZ - lcdPcbH/2 - c, lcdPcbZ + lcdPcbH/2 + c);
    color("#333") box(-lcdPcbW/2 + frRim + c, lcdPcbW/2 - frRim - c, yPcbF + lcdPcbT, yPcbF + lcdPcbT + lcdBackH + c, lcdPcbZ - lcdPcbH/2 + frRim, lcdPcbZ + lcdPcbH/2 - frRim);
    // header and the Dupont plugs on it
    color("#222") box(-hx - c, hx + c, yPcbF + lcdPcbT, yPcbF + lcdPcbT + 2.5 + 14 + c, hz - 1.27 - c, hz + 1.27 + c);
}
module lcdActive() color("#7fd0ff") box(-lcdActW/2, lcdActW/2, yAcrB - 0.01, yAcrB + 0.2, screenCentre[2] - lcdActH/2, screenCentre[2] + lcdActH/2);

module xiaoGhostLocal(c=0) {
    // lens barrel, camera module, both boards, the header rows with their Dupont plugs
    translate([0, -c, 0]) rotate([-90, 0, 0]) cylinder(d=camBarrelD + 2*c, h=camBarrelL + 2*c, $fn=32);
    box(-camModW/2 - c, camModW/2 + c, camBarrelL - c, xiaoExpFront + c, -camModW/2 - c, camModW/2 + c);
    box(xbU - xiaoL/2 - c, xbU + xiaoL/2 + c, xiaoExpFront - c, xiaoStackH + c, xbV - xiaoW/2 - c, xbV + xiaoW/2 + c);
    for (s = [-1, 1]) box(xbU - 7*2.54/2 - c, xbU + 7*2.54/2 + c, xiaoStackH, xiaoStackH + 2.5 + 14 + c, xbV + s*xiaoRow/2 - 1.27 - c, xbV + s*xiaoRow/2 + 1.27 + c);
}
module usbGhostLocal(c=0) {
    x0 = xbU + xiaoL/2;
    box(x0 - 0.3, x0 + plugL + c, wUsb - plugT/2 - c, wUsb + plugT/2 + c, xbV - plugW/2 - c, xbV + plugW/2 + c);
    // the cable leaves backwards from the elbow
    translate([x0 + plugL - plugCableD/2 - 1.5, wUsb, xbV]) rotate([-90, 0, 0]) cylinder(d=plugCableD + 2*c, h=30, $fn=24);
}
module xiaoGhost(c=0) camLocal() xiaoGhostLocal(c);
module usbGhost(c=0) camLocal() usbGhostLocal(c);
module tactGhost(c=0) {
    translate([kxPlate - tactBodyH - c, kY - tactW/2 - c, kZ - tactW/2 - c]) cube([tactBodyH + 2*c, tactW + 2*c, tactW + 2*c]);
    translate([kxPlate - tactH - c, kY, kZ]) rotate([0, 90, 0]) cylinder(d=tactPlungerD + 2*c, h=tactH - tactBodyH + c + eps, $fn=24);
}

// ---------------- Window: the acrylic and its drilling template --------------
module window2d() difference() {
    offset(r=acrR) square([acrW - 2*acrR, acrH - 2*acrR], center=true);
    translate([hCam[0], hCam[2] - winCz]) circle(d=camHoleD, $fn=48);
}
module windowAcrylic() translate([0, yAcrF, winCz]) rotate([-90, 0, 0]) mirror([0, 1, 0]) linear_extrude(acrT) window2d();

// Template: lay it on the sheet with its L-shaped skirt on a factory corner, score along the two
// free edges, file the corners to its radius, then drill the camera hole through its 4 mm guide.
jigT = 4;  jigSkirt = 2.5;  jigSkirtW = 1.6;
module windowJig() {
    difference() {
        union() {
            linear_extrude(jigT) offset(r=acrR) square([acrW - 2*acrR, acrH - 2*acrR], center=true);
            // the L: along the bottom and the left edge of the bounding box, skirt pointing up (= down in use)
            translate([-acrW/2 - jigSkirtW, -acrH/2 - jigSkirtW, 0]) cube([acrW*0.6, jigSkirtW, jigT + jigSkirt]);
            translate([-acrW/2 - jigSkirtW, -acrH/2 - jigSkirtW, 0]) cube([jigSkirtW, acrH*0.6, jigT + jigSkirt]);
        }
        translate([hCam[0], hCam[2] - winCz, -1]) cylinder(d=camHoleD + 0.2, h=jigT + 2, $fn=48);
        // lightening window, keeps the template stiff and quick to print
        translate([0, -4]) linear_extrude(10, center=true) offset(r=4) square([acrW - 26, acrH - 34], center=true);
    }
}

// ---------------- Head shell (warm grey) ------------------------------------
module headShellRaw() union() {
    intersection() {
        difference() { headSolid(); headHollow(); }
        box(-60, 60, -60, hySeam, 0, 200);
    }
    headShellFeatures();
}
// (one union of the small features, then one boolean with the shell: much faster in CGAL)
module headShellFeatures() {
    lipRing();
    // neck pilot bosses on the floor (teardrop: they print lying on their side)
    for (p = neckBolt) translate([p[0], p[1], hzF - eps]) tearCyl(neckBossD, neckBossH);
    // the two face-frame columns, from the front wall
    // (they start inside the curved front corner: the front is flat only for |x| <= 28)
    for (s = [-1, 1]) translate([s*frEarX, -37, frEarZ]) rotate([-90, 0, 0]) cylinder(d=6, h=yColEnd + 37);
    // cap columns, on the floor and under the top
    for (p = capPos) wallColumn(p[0], p[1], capColD, capColY0, hySeam, p[1] < winCz ? [0, -1] : [0, 1]);
    // knob bracket bosses on the left wall (axis X, teardrop toward -Y)
    for (s = [-1, 1]) translate([kxWall - eps, kY, kZ + s*kBossZ]) rotate([0, 90, 0]) rotate([0, 0, 90])
        linear_extrude(kxPlate - kxWall + eps) hull() { circle(d=kBossD); translate([-kBossD/2*1.41, 0]) square(0.01, center=true); }
}
module headShell() difference() {
    headShellRaw();
    headShellCuts();
}
module headShellCuts() {
    // window: the visible opening through the 0.8 mm lip, then the pocket for the acrylic
    xzRect(winOpenW, winOpenH, winOpenR, hyF - 1, 5, winCz);
    xzRect(acrW + 0.6, acrH + 0.6, acrR + 0.3, yAcrF, 10, winCz);
    // neck: M3 pilots up into the floor, and the cable hole
    for (p = neckBolt) translate([p[0], p[1], headZ0 - 1]) cylinder(d=m3_pilot, h=1 + hFloor + neckBossH - 0.8, $fn=24);
    translate([0, cableHoleY, headZ0 - 1]) rrect(cableHole[0], cableHole[1], cableHole[2], hFloor + 2);
    // face-frame pilots, cap pilots
    for (s = [-1, 1]) translate([s*frEarX, yColEnd + eps, frEarZ]) rotate([90, 0, 0]) cylinder(d=m3_pilot, h=6, $fn=24);
    for (p = capPos) translate([p[0], hySeam + lipH + 1, p[1]]) rotate([90, 0, 0]) cylinder(d=m3_pilot, h=10 + lipH + 1, $fn=24);
    // knob hole and the bracket pilots
    translate([-headW/2 - 1, kY, kZ]) rotate([0, 90, 0]) cylinder(d=kD + 0.5, h=hWall + 2, $fn=96);
    for (s = [-1, 1]) translate([kxPlate + eps, kY, kZ + s*kBossZ]) rotate([0, -90, 0]) cylinder(d=m3_pilot, h=7, $fn=24);
}

// ---------------- Back cap (warm grey) ----------------------------------------
module headCap() difference() {
    union() {
        intersection() {
            difference() { headSolid(); headHollow(); }
            box(-60, 60, hySeam, 60, 0, 200);
        }
        intersection() {
            for (p = capPos) translate([p[0], hySeam, p[1]]) rotate([-90, 0, 0]) cylinder(d=capBossD, h=headD/2 - hySeam - 1);
            headSolid();
        }
    }
    union() {
    lipCut(0.2);
    for (p = capPos) translate([p[0], hySeam - 1, p[1]]) rotate([-90, 0, 0]) {
        cylinder(d=m3_clear, h=30, $fn=24);
        translate([0, 0, 1 + 3 + lipH]) cylinder(d=m3_head + 0.4, h=30, $fn=32);   // counterbore from behind
    }
    // vent slots: air for the XIAO, and a path for the voice to the microphone
    for (i = [0:4]) translate([0, headD/2 - hWall - 1, winCz - 12 + i*6]) rotate([-90, 0, 0]) linear_extrude(hWall + 2)
        hull() for (s = [-1, 1]) translate([s*10, 0]) circle(d=1.8, $fn=16);
    }
}

// ---------------- Face frame (anthracite) --------------------------------------
// The XIAO sits in a pocket cut into a solid block on the back of the frame, above the screen.
// The pocket is the stack swept up its own board (+v): the XIAO drops in from the top, the block
// in front of the board is the front stop (the lens looks through a slot), lips at the two ends
// behind the base board are the back stop, and the USB-C plug, pushed in from the right through a
// notch closed above it, locks the board in place.
crBack = 1.0;                             // back lips behind the base board, at the two ends
crSweep = 30;
module xiaoSweepLocal(c=crC) {
    sweep = [0, 0, crSweep];
    hull() for (d = [[0, 0, 0], sweep]) translate(d)                                      // both boards
        box(xbU - xiaoL/2 - c, xbU + xiaoL/2 + c, xiaoExpFront - c, xiaoStackH + c, xbV - xiaoW/2 - crCv, xbV + xiaoW/2 + c);
    hull() for (d = [[0, 0, 0], sweep]) translate(d)                                      // header rows and their Dupont plugs
        box(xbU - 7*2.54/2 - c, xbU + 7*2.54/2 + c, xiaoStackH - 0.5, xiaoStackH + 2.5 + 14 + 10, xbV - xiaoRow/2 - 1.27 - c, xbV + xiaoRow/2 + 1.27 + c);
    // the plug's notch: open toward the plug (+u), closed above it
    x0 = xbU + xiaoL/2;
    box(x0 - 0.5, x0 + 40, wUsb - plugT/2 - 0.5, wUsb + plugT/2 + 0.5, xbV - plugW/2 - 0.5, xbV + plugW/2 + 0.5);
}
// side profile (y, z) of the block: from the plate back to behind the base board, 45 deg underneath
blkX = xiaoL/2 + crC + crWall;
blkBack = camToWorld([0, xiaoStackH + crBack, xbV + xiaoW/2])[1] + 1;
blkLow = camToWorld([0, xiaoStackH + crBack, xbV - xiaoW/2 - crCv - crWall])[2];
module faceFrame() {
    difference() {
        union() {
            xzRect(frW, frZ1 - frZ0, frR, yAcrB, yFB - yAcrB, (frZ0 + frZ1)/2);
            // ears over the shell's columns, chamfered underneath (the frame prints upright)
            for (s = [-1, 1]) mirror([s < 0 ? 1 : 0, 0, 0]) translate([0, yColEnd, 0]) rotate([90, 0, 0]) mirror([0, 0, 1])
                linear_extrude(earT) polygon([[frW/2 - 8, earZ0], [frW/2, earZ0], [earX1, frEarZ - 6], [earX1, frEarZ + 6], [frW/2 - 8, frEarZ + 6]]);
            // the XIAO block
            translate([camLens[0] + xbU, 0, 0]) sideExtrude(-blkX, blkX)
                polygon([[yFB - eps, frZ1], [blkBack, frZ1], [blkBack, blkLow], [yFB - eps, blkLow - (blkBack - yFB)]]);
        }
        union() {
            // screen: pocket from the front, bearing rim behind, open behind the header and the FPC
            box(-lcdPcbW/2 - 0.3, lcdPcbW/2 + 0.3, yAcrB - 1, yAcrB + lcdPocketD, lcdPcbZ - lcdPcbH/2 - 0.3, lcdPcbZ + lcdPcbH/2 + 0.3);
            // (the board bears on the two side rims only: the header end needs the room, and the
            // other end sits right under the XIAO)
            box(-lcdPcbW/2 + frRim, lcdPcbW/2 - frRim, yAcrB, yFB + eps, lcdPcbZ - lcdPcbH/2 - 0.3, lcdPcbZ + lcdPcbH/2 + 0.3);
            box(-lcdFpcW/2, lcdFpcW/2, yAcrB - 1, yAcrB + lcdPocketD, lcdPcbZ + lcdHdrEdge*(lcdPcbH/2 + 1.2) - 2, lcdPcbZ + lcdHdrEdge*(lcdPcbH/2 + 1.2) + 2);
            lcdGhost(0.3);
            // the XIAO's pocket, its plug and cable
            camLocal() { xiaoSweepLocal(); usbGhostLocal(0.5); }
            // the camera module and the lens: a slot straight up and out of the plate's top
            hull() for (dz = [0, 30]) translate([0, 0, dz]) camLocal()
                box(-camModW/2 - crC, camModW/2 + crC, -2, xiaoExpFront + crC, -camModW/2 - crC, camModW/2 + crC);
            // ear holes
            for (s = [-1, 1]) translate([s*frEarX, yColEnd - 1, frEarZ]) rotate([-90, 0, 0]) cylinder(d=m3_clear, h=earT + 2, $fn=24);
            // stay inside the shell: below the top, above the floor
            box(-60, 60, -60, 60, hzT - 0.4, 200);
            box(-60, 60, -60, 60, 0, frZ0);
        }
    }
}

// ---------------- Knob (orange) and switch bracket (anthracite) -----------------
module knob() translate([kxWall + 0.1, kY, kZ]) rotate([0, -90, 0]) {
    // local z = outwards (-X); z = 0 at the wall's inner face
    zo = kzo;
    translate([0, 0, 0]) cylinder(d=kD, h=zo - 0.8, $fn=96);
    translate([0, 0, zo - 0.8]) cylinder(d1=kD, d2=kD - 1.6, h=0.8, $fn=96);
    translate([0, 0, -2]) cylinder(d1=kFlangeD, d2=kD, h=2, $fn=96);           // cone: stop, self-centring
    translate([0, 0, -2.8]) cylinder(d=kFlangeD, h=0.8, $fn=96);
    translate([0, 0, -2.8 - 2.8]) cylinder(d=kStemD, h=2.8 + eps, $fn=32);    // stem onto the plunger
}
module switchBracket() difference() {
    translate([kxPlate, kY, kZ]) rotate([0, 90, 0]) rotate([0, 0, 90]) linear_extrude(kPlateT)
        offset(r=2) square([11 - 4, 2*kBossZ + 8 - 4], center=true);
    for (s = [-1, 1]) translate([kxPlate - 1, kY, kZ + s*kBossZ]) rotate([0, 90, 0]) cylinder(d=m3_clear, h=10, $fn=24);
    // the four legs locate the switch (turn it until they drop in); wire two diagonal legs
    for (sy = [-1, 1], sz = [-1, 1])
        translate([kxPlate - 1, kY + sy*tactLegs[1]/2, kZ + sz*tactLegs[0]/2]) rotate([0, 90, 0]) cylinder(d=1.4, h=10, $fn=12);
}

// ---------------- Numbers for hardware/scripts/check_head.py ----------------------
module checkValues() {
    v = [
        ["H0_PROVISIONAL", H0_PROVISIONAL ? 1 : 0], ["C8_AMEND", C8_AMEND ? 1 : 0],
        ["headW", headW], ["headD", headD], ["headR", headR], ["headZ0", headZ0], ["headZ1", headZ1], ["hZ1", hZ1],
        ["neckBolt", neckBolt], ["cableHole", cableHole], ["cableHoleY", cableHoleY],
        ["camPos", camPos], ["camTilt", camTilt], ["screenCentre", screenCentre],
        ["camLens", camLens], ["activeCentre", [screenCentre[0], yAcrB, screenCentre[2]]],
        ["active", [lcdActW, lcdActH]], ["winOpen", [winOpenW, winOpenZ0, winOpenZ1]],
        ["camHole", [hCam[0], hCam[2], camHoleD]], ["acrT", acrT], ["camGap", camGap],
        ["hzF", hzF], ["hzT", hzT], ["winLip", winLip],
        // screws: [length, clamped thickness, pilot depth]
        ["screw_neck", [14, gromFlangeT + bossT + deck + neckH, hFloor + neckBossH - 0.8]],
        ["screw_frame", [8, earT, 6]],
        ["screw_cap", [10, lipH + 3, 10]],
        ["screw_knob", [8, kPlateT, 7]],
        ["knob_gap", kxPlate - tactH - kxStem]
    ];
    for (e = v) echo(str("CHECK ", e[0], "=", e[1]));
}

// ---------------- Head views and print orientations ----------------------------
module headGhosts() {
    lcdGhost(); lcdActive();
    color("#2b2b2b") xiaoGhost();
    color("#444") usbGhost();
    color("WhiteSmoke") tactGhost();
}
module print_head_shell() translate([0, 0, -hyF]) rotate([90, 0, 0]) headShell();        // front face on the bed
module print_head_cap() translate([0, 0, headD/2]) rotate([-90, 0, 0]) headCap();         // back face on the bed
module print_face_frame() translate([0, 0, -frZ0]) faceFrame();                          // upright, as installed
module print_knob() translate([0, 0, -kxOuter]) rotate([0, -90, 0]) translate([0, -kY, -kZ]) knob();   // outer face on the bed
module print_switch_bracket() translate([0, 0, kxPlate + kPlateT]) rotate([0, 90, 0]) translate([0, -kY, -kZ]) switchBracket();   // plate on the bed
module print_window_jig() windowJig();

// =====================================================================
// Part dispatch (also used by hardware/scripts/check_head.py: PART="clash" intersects CLASH_A with CLASH_B)
CLASH_A = "";  CLASH_B = "";
module part(n) {
    if      (n == "body")           body();
    else if (n == "plinth")         plinth();
    else if (n == "sled")           sled();
    else if (n == "neck")           neck();
    else if (n == "grommet")        grommet();
    else if (n == "foot")           foot();
    else if (n == "head_shell")     headShell();
    else if (n == "head_cap")       headCap();
    else if (n == "face_frame")     faceFrame();
    else if (n == "knob")           knob();
    else if (n == "switch_bracket") switchBracket();
    else if (n == "window")         windowAcrylic();
    else if (n == "window_2d")      window2d();
    else if (n == "ghost_mr60")     kitGhost();
    else if (n == "ghost_ld2450")   ldGhost();
    else if (n == "ghost_speaker")  speakerGhost();
    else if (n == "ghost_lcd")      lcdGhost();
    else if (n == "ghost_lcd_active") lcdActive();
    else if (n == "ghost_xiao")     xiaoGhost();
    else if (n == "ghost_usb")      usbGhost();
    else if (n == "ghost_tact")     tactGhost();
    else if (n == "head_hollow")    difference() { box(-60, 60, -60, 60, 0, 200); headHollow(); }
    else if (n == "print_body")     print_body();
    else if (n == "print_plinth")   print_plinth();
    else if (n == "print_sled")     print_sled();
    else if (n == "print_tpu")      print_tpu();
    else if (n == "print_head_shell")     print_head_shell();
    else if (n == "print_head_cap")       print_head_cap();
    else if (n == "print_face_frame")     print_face_frame();
    else if (n == "print_knob")           print_knob();
    else if (n == "print_switch_bracket") print_switch_bracket();
    else if (n == "print_window_jig")     print_window_jig();
    else if (n == "check_values")   checkValues();
    else if (n == "clash")          intersection() { part(CLASH_A); part(CLASH_B); }
    else if (n == "exploded")       assemblyView(30);
    else if (n == "section")        assemblyView(0, true);
    else if (n == "head_exploded")  headExploded();
    else                            assemblyView(0);
}
if (PART != "none") part(PART);
