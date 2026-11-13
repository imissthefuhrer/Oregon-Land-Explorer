# Data-use review — 2026-11-13

This is a small personal prototype, not permission for bulk redistribution.

## OSIP 2024

Oregon GEO states OSIP imagery is available for all to use:
https://www.oregon.gov/eis/geo/Pages/statewide-data-initiatives.aspx
The state-hosted June 2025 funding strategy describes OSIP orthoimagery as released
into the public domain (Introduction):
https://www.oregon.gov/eis/geo/OGIC%20Documents/eis-geo-osip-longtermfundingstrategy-report-20250630.pdf
The 2024 service identifies statewide one-foot imagery acquired June–August 2024
by Surdex for the state, and credits Oregon Statewide Imagery Program:
https://imagery.oregonexplorer.info/arcgis/rest/services/OSIP_2024/OSIP_2024_WM/ImageServer

These state sources support offline use of the small imagery extract. Attribution
is preserved. For larger regions prefer official bulk imagery products over
thousands of dynamic exports and review the exact product metadata first.

## Marion parcels

The working endpoint describes assessor taxlots joined with taxation/revenue
attributes; its source is retained, not silently replaced:
https://services3.arcgis.com/SXXjryU22GsO8OEC/arcgis/rest/services/Parcels/FeatureServer/0
Its description does not establish a broad redistribution license or independently
verify this ArcGIS organization's publisher authority/currentness. Do not label it
a newly verified authoritative live assessor snapshot.

County GIS disclaimers state data are not surveys and accuracy/timeliness are not
warranted. The assessor portal also disclaims completeness and accuracy:
https://www.co.marion.or.us/Maps
https://mcasr.co.marion.or.us/
https://gis.co.marion.or.us/RoadIndex/Disclaimer.aspx

The county's published GIS policy says users must accept the applicable disclaimer:
https://www.co.marion.or.us/BOC/Policies/702-A.pdf
The first app displays approximate-boundary and unknown-access notices. No bulk
license is inferred from public HTTP availability. Before countywide packaging or
redistribution, verify this exact endpoint's publisher, data vintage, current
county GIS disclaimer and owner-field redistribution conditions with Marion County
GIS. No contact message has been sent. Only the requested small test is automated;
configuration safety limits prevent accidental countywide export.
