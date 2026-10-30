output "app_container" {
  description = "Nom du conteneur API"
  value       = docker_container.app.name
}

output "app_url" {
  description = "URL locale de l'API déployée"
  value       = "http://localhost:${var.app_external_port}"
}

output "network" {
  description = "Réseau Docker"
  value       = docker_network.medlink.name
}
