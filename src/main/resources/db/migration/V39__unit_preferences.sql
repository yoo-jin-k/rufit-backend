-- 계정별 무게 표시 단위입니다. 세트 무게는 항상 kg로 저장하고, 화면에서만 이 단위로 바꿔 보여 줍니다.
-- 행이 없으면 kg입니다.
CREATE TABLE unit_preferences (
    owner_name varchar(100) PRIMARY KEY,
    weight_unit varchar(2) NOT NULL CHECK (weight_unit IN ('KG', 'LB')),
    version bigint NOT NULL CHECK (version > 0)
);
