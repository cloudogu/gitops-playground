# k8s-gitops-playground

Reproducible infrastructure to showcase GitOps workflows. Derived from our [consulting experience](https://cloudogu.com/en/consulting/).

## Prerequisites

To be able to set up the infrastructure you are required to have **k3s** and **helm** set up.
If you use the provided script, these components will be installed, if not done yet.


## Install k3s

You can use your own k3s cluster, or use the script provided.
Run this script from repo root with:

`./scripts/init-cluster.sh`

If you use your own cluster, note that jenkins relies on the `--docker` mode to be enabled.

In a real-life scenario, it would make sense to run Jenkins agents outside the cluster for security and load reasons, 
but in order to simplify the setup for this playground we use this slightly dirty workaround: 
Jenkins builds on the master and uses the docker agent that also runs the k8s pods. That's why we need the k3s' 
`--docker` mode.
 
**Don't use a setup such as this in production!** The diagrams bellow show an overview of the playground's architecture,
 and a possible production scenario using our [Ecosystem](https://cloudogu.com/en/ecosystem/) (more secure and better build performance using ephemeral build agents spawned in the cloud).


|Playground on local machine | A possible production environment with [Cloudogu Ecosystem](https://cloudogu.com/en/ecosystem/)|
|--------------------|----------|
|[![Playground on local machine](http://www.plantuml.com/plantuml/svg/dLLTJ-Cu57tFh_02hsdOLY24gWee2tGfT0LRZKR3QEJQjwcdZXtvWu8G_tqSrqqQIgRLv05kVCyvFYVNIS-qmShOZ4VHNqmGRYbOnT7Cc5oV9ed2YrRApCnEh0P5f30WJ8l8BCaOcJ7WISwnAFZnt4v02J2WOvqhvlud6TO6LA90Iwi89FEJiXTRmV44ED2uVPGJqs9B3nIcJ00Qz4VtuuVX3ZwCpJ5LdSe7SzruwX2bZUTsgzqwhEKB-ebJoAHevOxuQP_2Sw6d4oh97DGEGEUo6ULjuGLecK5ybFm4CMT2xupNhuBi39x8bsPiXWqeXddn079hGhBg-VU7e_7bw7gysmm8bvRAr5P3MAryE0erBsjx94PAhelp4Imtm0f9dMYIDTZzSJ4S_uFssntSeJIAXAI0zhvAgz2Dr6OFOOrcha3Txi4gUA-7zgV-GczHXDobW1WiKkwetOFbt7jFuj8Nw1939eLNMhOyPPFdeZZIRKUZFhT5T4zng-ZDWpS8nMmTuuhR5m-Tkg94beMHAPWPg4fbKyaDtWcD-WFq02dJHhrwGHLk6ed1y_b_W_WHr7EmZ7EslDl6Azv_3iQJGbrItikg8Jp9dC9Z_ow-mmAdOMktclqkQCfCuJVFNT1S1hTvKM_ZN3CpodL58r9CMHgOkraExwuJ-tTPeGoNSmxDO7uB_yxpa8ijp3hxNbAest5_twCeP4JnjncZ5CJttfh_ijkDutKXT8footaKa4q8FOjMvwIf7uNXeZZkXNV0jPGLXGk3cG2betLU2Vk8UX8JopX5B7FPTLISU2zQUGokGwjN7KRNS47vArhzjkZALgyywe5UjUeA2so8owETqnmhd26ib5pueVKbbmfT3C_xjz7xEaIuH1vn8HK5vN7rpqyTZucHQad0P9ugQGNrfF8hLPVQ__lu-EIaMBsImeIrGtyr2TUbD_qWzEaMTRdoR3P8VhaP_5-JQjZ8kzxsK9WmeAQOxBnPisyXpH-hd86WDkE_0000)](http://www.plantuml.com/plantuml/svg/dLLTJ-Cu57tFh_02hsdOLY24gWee2tGfT0LRZKR3QEJQjwcdZXtvWu8G_tqSrqqQIgRLv05kVCyvFYVNIS-qmShOZ4VHNqmGRYbOnT7Cc5oV9ed2YrRApCnEh0P5f30WJ8l8BCaOcJ7WISwnAFZnt4v02J2WOvqhvlud6TO6LA90Iwi89FEJiXTRmV44ED2uVPGJqs9B3nIcJ00Qz4VtuuVX3ZwCpJ5LdSe7SzruwX2bZUTsgzqwhEKB-ebJoAHevOxuQP_2Sw6d4oh97DGEGEUo6ULjuGLecK5ybFm4CMT2xupNhuBi39x8bsPiXWqeXddn079hGhBg-VU7e_7bw7gysmm8bvRAr5P3MAryE0erBsjx94PAhelp4Imtm0f9dMYIDTZzSJ4S_uFssntSeJIAXAI0zhvAgz2Dr6OFOOrcha3Txi4gUA-7zgV-GczHXDobW1WiKkwetOFbt7jFuj8Nw1939eLNMhOyPPFdeZZIRKUZFhT5T4zng-ZDWpS8nMmTuuhR5m-Tkg94beMHAPWPg4fbKyaDtWcD-WFq02dJHhrwGHLk6ed1y_b_W_WHr7EmZ7EslDl6Azv_3iQJGbrItikg8Jp9dC9Z_ow-mmAdOMktclqkQCfCuJVFNT1S1hTvKM_ZN3CpodL58r9CMHgOkraExwuJ-tTPeGoNSmxDO7uB_yxpa8ijp3hxNbAest5_twCeP4JnjncZ5CJttfh_ijkDutKXT8footaKa4q8FOjMvwIf7uNXeZZkXNV0jPGLXGk3cG2betLU2Vk8UX8JopX5B7FPTLISU2zQUGokGwjN7KRNS47vArhzjkZALgyywe5UjUeA2so8owETqnmhd26ib5pueVKbbmfT3C_xjz7xEaIuH1vn8HK5vN7rpqyTZucHQad0P9ugQGNrfF8hLPVQ__lu-EIaMBsImeIrGtyr2TUbD_qWzEaMTRdoR3P8VhaP_5-JQjZ8kzxsK9WmeAQOxBnPisyXpH-hd86WDkE_0000)  | [![A possible production environment](http://www.plantuml.com/plantuml/svg/dLNlRzis4Fsklu9r-rLMvw25KHX64otjl3hrqCuOXcqOQF8iSwP8WZ_i6aN_zvreAMOJQp6c3md5U-_Tuzs9VkiyjJxKCdic59E1Gx2IRBmtxarHMBeVLi9lmYeui4mh3yeFcQwBMh2D05aOILL3pxTX1LQ11vSxOmN-BshgFDZ2WjF1CYYsu_jO7fIaAv30yz4hm_nGD1QoPnpMGW6PbOkFoq_p2tpuO2YtLoSV0gFv6X7tDdQZuMZuCZrVfr-WGgtLq23nR9p3hj5p1TTi2_O28BhiHqLRT0zErv2_Alu1jHNANOnt1yKk1g_qbtviJJrOGMLJ01DyF5bQ_tA-MFwuUB-yh0TCwi1r5KvDw7OUZ4Bg9SrHUAsbQ-OZ-kY5DPAkSApkiVltpNnvIvwFhkWLvraMNP31xpfOSbmRgtU2WnsGhrb6yB70F5ML138eMy_abcbtGBlrW51763WocRpeJZIT8fKaJw8mUO7Mfd-gp-LTVaSLhS162TojErOV4okBp6jZo2SmEjAeCStw4jvxuHV6dQXCgxMe7h6_VVVMVunsC57uv-EAtCN-HUqsIBu-biQ_sN-8tPeDr62f95WQvLqgzr5pE4F6AKRYefpXsIhXl3r4acrVpZ1mY93nOUn8ASEzQTnD37jTWT-0JRPwT9fRJOAF6ktwAShrBxFyCzXTcvaQSNphvpNVDHBVoV0bKjQuo1xthAqmrhYT39B_iH6SW_fhcdQyYqxoFAqkUKDCa3Bha3q8jycwwlR5iyOWoVCqQwlUXgQdOsC03Sa3wNu6NDOMCYfqkJf-cmub7cIkumULdNGdUTSHZEwfPsZC8S5GIsj2hTNxsDzIIst9pVow_91zIo7nauwG6sqvs0Zy_Yv1mWclqCYJyDG2yzeU2PHjAwk3uf7KtL0Ff9x_yFhrUDpiljFAjtiluzCdd4gVyqF8-HIfN3ABvoL_-sdsQohLaoYwVh8eGdcmQyekJjRlMsd_VrNUWkAXbly2)](http://www.plantuml.com/plantuml/svg/dLNlRzis4Fsklu9r-rLMvw25KHX64otjl3hrqCuOXcqOQF8iSwP8WZ_i6aN_zvreAMOJQp6c3md5U-_Tuzs9VkiyjJxKCdic59E1Gx2IRBmtxarHMBeVLi9lmYeui4mh3yeFcQwBMh2D05aOILL3pxTX1LQ11vSxOmN-BshgFDZ2WjF1CYYsu_jO7fIaAv30yz4hm_nGD1QoPnpMGW6PbOkFoq_p2tpuO2YtLoSV0gFv6X7tDdQZuMZuCZrVfr-WGgtLq23nR9p3hj5p1TTi2_O28BhiHqLRT0zErv2_Alu1jHNANOnt1yKk1g_qbtviJJrOGMLJ01DyF5bQ_tA-MFwuUB-yh0TCwi1r5KvDw7OUZ4Bg9SrHUAsbQ-OZ-kY5DPAkSApkiVltpNnvIvwFhkWLvraMNP31xpfOSbmRgtU2WnsGhrb6yB70F5ML138eMy_abcbtGBlrW51763WocRpeJZIT8fKaJw8mUO7Mfd-gp-LTVaSLhS162TojErOV4okBp6jZo2SmEjAeCStw4jvxuHV6dQXCgxMe7h6_VVVMVunsC57uv-EAtCN-HUqsIBu-biQ_sN-8tPeDr62f95WQvLqgzr5pE4F6AKRYefpXsIhXl3r4acrVpZ1mY93nOUn8ASEzQTnD37jTWT-0JRPwT9fRJOAF6ktwAShrBxFyCzXTcvaQSNphvpNVDHBVoV0bKjQuo1xthAqmrhYT39B_iH6SW_fhcdQyYqxoFAqkUKDCa3Bha3q8jycwwlR5iyOWoVCqQwlUXgQdOsC03Sa3wNu6NDOMCYfqkJf-cmub7cIkumULdNGdUTSHZEwfPsZC8S5GIsj2hTNxsDzIIst9pVow_91zIo7nauwG6sqvs0Zy_Yv1mWclqCYJyDG2yzeU2PHjAwk3uf7KtL0Ff9x_yFhrUDpiljFAjtiluzCdd4gVyqF8-HIfN3ABvoL_-sdsQohLaoYwVh8eGdcmQyekJjRlMsd_VrNUWkAXbly2)   |

## Apply apps to cluster

[`scripts/apply.sh`](scripts/apply.sh)

The scripts also prints a little intro on how to get started with a GitOps deployment.

## Remove apps from cluster

`scripts/destroy.sh`

## Login

### Jenkins

Find jenkins on http://localhost:9090

Admin user: Same as SCM-Manager - `scmadmin/scmadmin`
Change in `jenkins-credentials.yaml` if necessary.

### SCM-Manager

Find scm-manager on http://localhost:9091

Login with `scmadmin/scmadmin`

