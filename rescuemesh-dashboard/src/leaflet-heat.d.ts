/* leaflet.heat ships no TypeScript types; it extends L with
 * L.heatLayer(points, options). Declared here so the side-effect
 * import type-checks; the call site casts L for the one method. */
declare module "leaflet.heat";
