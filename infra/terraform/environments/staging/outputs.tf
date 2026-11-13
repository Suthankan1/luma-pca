output "load_balancer_dns" {
  description = "Point the academy's domain here (CNAME or alias)."
  value       = module.compute.alb_dns_name
}

output "zoom_webhook_url" {
  value = "https://${var.domain}/api/v1/zoom/webhooks"
}
