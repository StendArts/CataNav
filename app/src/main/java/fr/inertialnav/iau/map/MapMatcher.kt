package fr.inertialnav.iau.map

import fr.inertialnav.iau.core.geo.GeoPosition

/**
 * Résultat d'appariement carte.
 * @param position point praticable le plus proche (WGS84)
 * @param distanceM distance entre la position estimée et ce point
 * @param onNetwork la position estimée est déjà sur le réseau praticable
 */
data class MapMatch(val position: GeoPosition, val distanceM: Double, val onNetwork: Boolean)

/**
 * Hybridation carte : fournit le point praticable (galerie, salle) le plus proche d'une
 * position estimée. KAL s'en sert comme recalage pour maintenir la position sur le réseau.
 */
interface MapMatcher {
    val name: String

    /** La position est-elle dans l'emprise de la carte ? */
    fun covers(latitudeDeg: Double, longitudeDeg: Double): Boolean

    /** Point praticable le plus proche dans un rayon donné, ou null s'il n'y en a aucun. */
    fun nearest(latitudeDeg: Double, longitudeDeg: Double, searchRadiusM: Double): MapMatch?
}
