use avb2::{
    add_hash_footer, add_hashtree_footer, erase_footer, load_avb_image, make_vbmeta_image,
    verify_image, AlgorithmType, AvbDescriptor, AvbPrivateKey, ChainPartitionDescriptor,
    HashFooterOptions, HashtreeFooterOptions,
};
use std::fs;
use tempfile::tempdir;

#[test]
fn end_to_end_hash_hashtree_and_vbmeta_chain() {
    let dir = tempdir().unwrap();
    let priv_key = AvbPrivateKey::generate(2048).unwrap();
    let pub_key = priv_key.public_key().unwrap();

    // 1. Hash footer on boot.img
    let boot = dir.path().join("boot.img");
    fs::write(&boot, vec![0xABu8; 65536]).unwrap();
    add_hash_footer(
        &boot,
        HashFooterOptions {
            partition_name: "boot",
            partition_size: 262144,
            algorithm: AlgorithmType::Sha256Rsa2048,
            key: Some(&priv_key),
            salt: None,
            rollback_index: 0,
            flags: 0,
            props: vec![("com.android.build.boot.os_version".into(), "14".into())],
            release_string: None,
        },
    )
    .unwrap();
    let res = verify_image(&boot, Some(&pub_key.raw_avb_key), false, None, false).unwrap();
    assert_eq!(res.len(), 1);

    // 2. Multi-level hashtree footer on system.img (1 MiB -> 256 blocks -> 2 hash levels!)
    let system = dir.path().join("system.img");
    let mut sys_data = vec![0u8; 1024 * 1024];
    for (i, b) in sys_data.iter_mut().enumerate() {
        *b = (i % 251) as u8;
    }
    fs::write(&system, &sys_data).unwrap();
    let (ftr, ht) = add_hashtree_footer(
        &system,
        HashtreeFooterOptions {
            partition_name: "system",
            partition_size: None,
            algorithm: AlgorithmType::Sha256Rsa2048,
            key: Some(&priv_key),
            salt: None,
            rollback_index: 0,
            flags: 0,
            props: vec![],
            release_string: None,
            block_size: 4096,
        },
        |_, _| {},
    )
    .unwrap();
    assert_eq!(ftr.original_image_size, 1024 * 1024);
    assert!(ht.tree_size >= 12288); // level0=8192 + level1=4096
    verify_image(&system, Some(&pub_key.raw_avb_key), false, None, false).unwrap();

    // 3. Top-level vbmeta chaining boot & system
    let vbmeta = dir.path().join("vbmeta.img");
    make_vbmeta_image(
        &vbmeta,
        vec![
            AvbDescriptor::ChainPartition(ChainPartitionDescriptor {
                rollback_index_location: 1,
                partition_name: "boot".into(),
                public_key: pub_key.raw_avb_key.clone(),
                flags: 0,
            }),
            AvbDescriptor::ChainPartition(ChainPartitionDescriptor {
                rollback_index_location: 2,
                partition_name: "system".into(),
                public_key: pub_key.raw_avb_key.clone(),
                flags: 0,
            }),
        ],
        AlgorithmType::Sha256Rsa2048,
        Some(&priv_key),
        0,
        0,
        65536,
        None,
    )
    .unwrap();

    let chain_res = verify_image(&vbmeta, Some(&pub_key.raw_avb_key), true, None, false).unwrap();
    assert_eq!(chain_res.len(), 3);

    // 4. Negative test: tamper 1 byte in system.img -> verification MUST fail!
    let mut tampered = fs::read(&system).unwrap();
    tampered[4096] ^= 0xFF;
    fs::write(&system, &tampered).unwrap();
    assert!(verify_image(&system, Some(&pub_key.raw_avb_key), false, None, false).is_err());

    // 5. Erase footer restores exact original size
    assert!(erase_footer(&boot).unwrap());
    assert_eq!(fs::metadata(&boot).unwrap().len(), 65536);
    assert!(load_avb_image(&boot).is_err());
}
