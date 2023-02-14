package com.hanamja.moa.api.service.group;

import com.hanamja.moa.api.dto.group.request.MakingGroupRequestDto;
import com.hanamja.moa.api.dto.group.request.ModifyingGroupRequestDto;
import com.hanamja.moa.api.dto.group.request.RemovingGroupRequestDto;
import com.hanamja.moa.api.dto.group.response.GroupInfoListResponseDto;
import com.hanamja.moa.api.dto.group.response.GroupInfoResponseDto;
import com.hanamja.moa.api.entity.group.Group;
import com.hanamja.moa.api.entity.group.GroupRepository;
import com.hanamja.moa.api.entity.group_hashtag.GroupHashtag;
import com.hanamja.moa.api.entity.group_hashtag.GroupHashtagRepository;
import com.hanamja.moa.api.entity.hashtag.Hashtag;
import com.hanamja.moa.api.entity.hashtag.HashtagRepository;
import com.hanamja.moa.api.entity.user.User;
import com.hanamja.moa.api.entity.user.UserRepository;
import com.hanamja.moa.api.entity.user_group.UserGroup;
import com.hanamja.moa.api.entity.user_group.UserGroupRepository;
import com.hanamja.moa.exception.custom.NotFoundException;
import com.hanamja.moa.exception.custom.UserInputException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
@Slf4j
@Transactional(readOnly = true)
public class GroupService {
    private final UserRepository userRepository;
    private final UserGroupRepository userGroupRepository;
    private final GroupRepository groupRepository;
    private final GroupHashtagRepository groupHashtagRepository;
    private final HashtagRepository hashtagRepository;

    @Transactional
    public GroupInfoResponseDto makeNewGroup(MakingGroupRequestDto makingGroupRequestDto) {
        // TODO: 로그인 구현 후 @AuthenticationPrincipal User user 추가 필요

        User user = userRepository.findById(1L).orElseThrow(
                () -> NotFoundException
                        .builder()
                        .httpStatus(HttpStatus.BAD_REQUEST)
                        .message("유효하지 않은 사용자입니다.")
                        .build()
        );

        // 재학생인지 검증
        validateSenior(user);

        // Hashtag #으로 파싱 후 분리, 저장 (다른 메소드로 분리 구현)
        List<Hashtag> hashtagList = saveHashtags(makingGroupRequestDto.getHashtags());

        Group newGroup = MakingGroupRequestDto.toEntity(makingGroupRequestDto, user);
        groupRepository.save(newGroup);

        // GroupHashtag 생성 및 Group과의 관계 설정, 저장
        hashtagList.stream().map(
                x -> GroupHashtag
                        .builder()
                        .group(newGroup)
                        .hashtag(x)
                        .build()
        ).forEach(groupHashtagRepository::save);

        return GroupInfoResponseDto.from(newGroup);
    }

    private void validateSenior(User user) {
        if (user.isFreshman()) {
            log.info("신입생의 모임 생성 시도 발생 - 학번: {}, 이름: {}", user.getStudentId(), user.getName());
            throw UserInputException
                    .builder()
                    .httpStatus(HttpStatus.UNAUTHORIZED)
                    .message("신입생은 모임을 생성할 수 없습니다.")
                    .build();
        }
    }

    private void validateMaker(User user, Group group) {
        if (!group.getMaker().getId().equals(user.getId())) {
            throw UserInputException
                    .builder()
                    .httpStatus(HttpStatus.UNAUTHORIZED)
                    .message("해당 모임에 접근할 권한이 없습니다.")
                    .build();
        }
    }

    @Transactional
    protected List<Hashtag> saveHashtags(String hashtagString) {
        List<String> hashtagStringList = new java.util.ArrayList<>(List.of(hashtagString.split("#")));
        hashtagStringList.remove(0);

        return hashtagStringList.stream().map(
                x -> {
                    if (hashtagRepository.existsByName(x)) {
                        // 이미 같은 이름의 해시태그가 존재하는 경우 변경 시간 업데이트
                        Hashtag existingHastag = hashtagRepository.findByName(x).orElseThrow();
                        existingHastag.updateTouchedAt();

                        // 찾아온 해시태그를 리턴 - TODO: orElseThrow에 500 Internal 에러 처리 추가
                        return hashtagRepository.findByName(x).orElseThrow();
                    } else {
                        // 같은 이름의 해시태그가 존재하지 않는 경우 새로운 해시태그 생성
                        return hashtagRepository.save(
                                Hashtag
                                        .builder()
                                        .name(x)
                                        .build()
                        );
                    }
                }
        ).collect(Collectors.toList());
    }

    @Transactional
    public GroupInfoResponseDto modifyExistingGroup(ModifyingGroupRequestDto modifyingGroupRequestDto) {
        // TODO: 로그인 구현 후 @AuthenticationPrincipal User user 추가 필요

        User user = userRepository.findById(1L).orElseThrow(
                // TODO: Exception 구현 후 사용자를 찾지 못한 경우 unchecked Exception 던지기
        );

        // 재학생인지 검증
        validateSenior(user);

        // groupId로 group 찾아오기
        Group existingGroup = groupRepository.findById(modifyingGroupRequestDto.getId()).orElseThrow(
                // TODO: group이 없을 때 400 Bad Request 던지도록 구현 필요
        );

        validateMaker(user, existingGroup);

        if (existingGroup.getCurrentPeopleNum() > modifyingGroupRequestDto.getMaxPeopleNum()) {
            // TODO: 새로운 group 최대 인원수보다 현재 인원수가 많으면 400 Bad Request
        }

        // group 정보 수정
        existingGroup.modifyGroupInfo(
                modifyingGroupRequestDto.getName(),
                modifyingGroupRequestDto.getDescription(),
                modifyingGroupRequestDto.getMeetingAt(),
                modifyingGroupRequestDto.getMaxPeopleNum()
        );

        // 기존 해시태그 정보 삭제
        groupHashtagRepository.deleteAllByGroup_Id(existingGroup.getId());

        // 새로운 해시태그 정보 등록 및 관계 테이블에 저장
        List<Hashtag> hashtagList = saveHashtags(modifyingGroupRequestDto.getHashtags());
        hashtagList.stream().map(
                x -> GroupHashtag
                        .builder()
                        .group(existingGroup)
                        .hashtag(x)
                        .build()
        ).forEach(groupHashtagRepository::save);

        // 수정된 group 정보 반영
        return GroupInfoResponseDto.from(groupRepository.save(existingGroup));
    }

    @Transactional
    public GroupInfoResponseDto removeExistingGroup(RemovingGroupRequestDto removingGroupRequestDto) {
        User user = userRepository.findById(1L).orElseThrow(
                // TODO: Exception 구현 후 사용자를 찾지 못한 경우 unchecked Exception 던지기
        );

        // 재학생인지 검증
        validateSenior(user);

        // groupId로 group 찾아오기
        Group existingGroup = groupRepository.findById(removingGroupRequestDto.getId()).orElseThrow(
                // TODO: group이 없을 때 400 Bad Request 던지도록 구현 필요
        );

        GroupInfoResponseDto removedGroupDto = GroupInfoResponseDto.from(existingGroup);

        // UserGroup, GroupHashtag에서 해당 Group 모두 삭제
        userGroupRepository.deleteAllByGroup_Id(removingGroupRequestDto.getId());
        groupHashtagRepository.deleteAllByGroup_Id(removingGroupRequestDto.getId());

        // Group 삭제
        groupRepository.deleteById(removingGroupRequestDto.getId());

        return removedGroupDto;
    }

    public GroupInfoResponseDto join(Long groupId, User user) {

        Group group = groupRepository.findById(groupId).orElseThrow(
                () -> new NotFoundException("해당 그룹을 찾을 수 없습니다.")
        );

        UserGroup userGroup = UserGroup
                .builder()
                .progress("")
                .joiner(user)
                .group(group)
                .build();

        userGroupRepository.save(userGroup);

        return GroupInfoResponseDto.from(group);
    }

    public GroupInfoListResponseDto getMyGroupList(User user) {
        Long userId = user.getId();
        List<Group> groupList = groupRepository.findAllByUserId(userId);

        List<GroupInfoResponseDto> items = groupList.stream().map(GroupInfoResponseDto::from).collect(Collectors.toList());

        return GroupInfoListResponseDto.of(items);
    }

    public GroupInfoResponseDto quit(Long groupId, User user) {

        UserGroup userGroup = userGroupRepository.findByGroupIdAndJoinerId(groupId, user.getId())
                .orElseThrow(() -> new NotFoundException("해당 그룹을 찾을 수 없습니다."));

        Group group = userGroup.getGroup();
        userGroupRepository.delete(userGroup);

        
        return GroupInfoResponseDto.from(group);
    }
}
