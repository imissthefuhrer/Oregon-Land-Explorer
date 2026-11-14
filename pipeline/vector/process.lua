-- Oregon Land Explorer outdoor schema v1. Original profile; no external shapefiles.
-- Multipolygons are assembled by Tilemaker before way_function.
node_keys = { "place", "tourism", "natural", "amenity", "highway" }
way_keys = { "highway", "railway", "waterway", "natural", "landuse", "leisure", "boundary", "building", "amenity" }
function init_function() end
function exit_function() end
function attrs(class)
    Attribute("class", class)
    for _, k in ipairs({"name", "ref", "surface", "access", "foot", "bicycle", "motor_vehicle", "bridge", "tunnel"}) do
        local value = Find(k)
        if value ~= "" then Attribute(k, value) end
    end
end
function node_function()
    local place=Find("place")
    local name=Find("name")
    if name=="" then return end
    if place~="" then Layer("place",false); attrs(place); MinZoom(9)
    elseif Find("tourism")~="" or Find("natural")=="waterfall" or Find("amenity")=="parking" or Find("highway")=="trailhead" then
        Layer("poi",false); attrs(Find("natural")~="" and Find("natural") or Find("tourism")); MinZoom(13)
    end
end
function way_function()
    local h=Find("highway")
    if h~="" and h~="proposed" and h~="construction" then
        Layer("roads",false); attrs(h)
        if h=="motorway" or h=="trunk" or h=="primary" then MinZoom(8)
        elseif h=="secondary" or h=="tertiary" then MinZoom(9)
        elseif h=="path" or h=="footway" or h=="steps" or h=="bridleway" or h=="cycleway" or h=="track" then MinZoom(12)
        else MinZoom(11) end
    end
    if Find("railway")=="rail" then Layer("rail",false); attrs("rail"); MinZoom(11) end
    local waterway=Find("waterway")
    if waterway~="" then Layer("waterway",false); attrs(waterway); MinZoom(waterway=="river" and 8 or 12) end
    if not IsClosed() then return end
    local natural=Find("natural"); local landuse=Find("landuse"); local leisure=Find("leisure")
    local class=""
    if natural=="water" or landuse=="reservoir" or waterway=="riverbank" then
        Layer("water",true); attrs("water"); MinZoom(8)
    elseif natural=="wood" or landuse=="forest" then class="wood"
    elseif leisure=="park" or leisure=="nature_reserve" or Find("boundary")=="national_park" then class="park"
    elseif landuse=="farmland" or landuse=="farmyard" or landuse=="orchard" or landuse=="vineyard" then class="farmland"
    elseif natural=="wetland" then class="wetland"
    elseif natural=="scrub" or natural=="grassland" or landuse=="meadow" or landuse=="grass" then class="grass"
    end
    if class~="" then Layer("land",true); attrs(class); MinZoom(8) end
    if Find("building")~="" then Layer("building",true); attrs("building"); MinZoom(14) end
    if Find("name")~="" and (class=="park" or natural=="water") then LayerAsCentroid("poi"); attrs(class=="park" and "park" or "water"); MinZoom(11) end
end
