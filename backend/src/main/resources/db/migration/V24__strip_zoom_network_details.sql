-- Privacy: participant network and device details are not needed for attendance and must not be
-- kept (findings of the 13 Nov 2026 test webinar). New receipts are stored without them
-- (ZoomWebhookReceipts.DROPPED); this removes them from receipts stored earlier.
UPDATE zoom_webhook_receipts
SET payload = payload #- '{payload,object,participant,public_ip}' #- '{payload,object,participant,private_ip}'
                      #- '{payload,object,participant,ip_address}' #- '{payload,object,participant,location}'
                      #- '{payload,object,participant,device}' #- '{payload,object,participant,network_type}'
                      #- '{payload,object,participant,mac_addr}' #- '{payload,object,participant,internal_ip_addresses}'
WHERE payload #> '{payload,object,participant}' ?| ARRAY['public_ip', 'private_ip', 'ip_address', 'location', 'device',
                                                        'network_type', 'mac_addr', 'internal_ip_addresses'];
