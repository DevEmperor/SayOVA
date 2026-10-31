/*
 * The website's only script: the living cloud orb, the same WebGL shader as on the pairing page
 * (pair/src/page/orb.ts). A CSS-scaled still image stutters in Firefox, which moves such a slow scale in
 * whole pixels.
 */
import { startOrb } from "../../pair/src/page/orb.ts";

const orb = document.querySelector<HTMLElement>(".orb");
if (orb) startOrb(orb);
