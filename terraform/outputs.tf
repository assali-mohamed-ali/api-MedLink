output "app_container" {
  description = "Nom du conteneur API"
  value       = docker_container.app.name
}

output "network" {
  description = "Réseau Docker"
  value       = docker_network.medlink.name
}
